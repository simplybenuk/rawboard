#include <jni.h>
#include "llama.h"
#include "ggml.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr size_t kMaxOutputBytes = 65536;
constexpr uint64_t kMaxMetadataBytes = 64ULL * 1024 * 1024;
class BridgeError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// Integer handles keep cancel/destroy safe even if a future caller gets its
// lifecycle wrong. generate holds a shared reference until all CPU work ends.
struct Session {
    std::atomic<bool> cancelled{false};
    std::atomic<bool> running{false};
    Clock::time_point deadline;
};
std::mutex sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
jlong next_session = 1;
std::timed_mutex inference_mutex;
std::once_flag backend_once;

std::shared_ptr<Session> find_session(jlong id) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    const auto it = sessions.find(id);
    return it == sessions.end() ? nullptr : it->second;
}

bool stopped(const Session & s) {
    return s.cancelled.load(std::memory_order_relaxed) || Clock::now() >= s.deadline;
}
void check_stop(const Session & s) {
    if (s.cancelled.load(std::memory_order_relaxed)) {
        throw BridgeError("Local editing was cancelled. Your original is unchanged.");
    }
    if (Clock::now() >= s.deadline) {
        throw BridgeError("Local editing timed out. Your original is unchanged.");
    }
}
bool abort_decode(void * data) { return stopped(*static_cast<Session *>(data)); }
bool load_progress(float, void * data) { return !stopped(*static_cast<Session *>(data)); }
void discard_log(ggml_log_level, const char *, void *) {}

void throw_java(JNIEnv * env, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}

void append_utf8(std::string & out, uint32_t cp) {
    if (cp <= 0x7f) out.push_back(static_cast<char>(cp));
    else if (cp <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
}

// JNI's GetStringUTFChars uses modified UTF-8, which corrupts supplementary
// characters for a normal UTF-8 tokenizer. Convert Java UTF-16 explicitly.
std::string java_utf8(JNIEnv * env, jstring value, jsize max_units) {
    if (value == nullptr) throw BridgeError("Missing local editing input.");
    const jsize length = env->GetStringLength(value);
    if (length > max_units) throw BridgeError("This draft is too long for the local model. Your original is unchanged.");
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw BridgeError("The local model could not read its input.");
    struct Release { JNIEnv * env; jstring value; const jchar * chars;
        ~Release() { env->ReleaseStringChars(value, chars); } } release{env, value, chars};
    std::string result;
    result.reserve(static_cast<size_t>(length) * 2);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
                cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
            } else cp = 0xfffd;
        } else if (cp >= 0xdc00 && cp <= 0xdfff) cp = 0xfffd;
        if (cp == 0) throw BridgeError("The local model input contains an unsupported character.");
        append_utf8(result, cp);
    }
    return result;
}

// Validate a completed UTF-8 result and create a real Java UTF-16 string.
jstring java_string(JNIEnv * env, const std::string & value) {
    std::vector<jchar> result;
    result.reserve(value.size());
    for (size_t i = 0; i < value.size();) {
        const auto first = static_cast<uint8_t>(value[i++]);
        uint32_t cp;
        int continuation;
        uint32_t minimum;
        if (first <= 0x7f) { cp = first; continuation = 0; minimum = 0; }
        else if (first >= 0xc2 && first <= 0xdf) { cp = first & 0x1f; continuation = 1; minimum = 0x80; }
        else if (first >= 0xe0 && first <= 0xef) { cp = first & 0x0f; continuation = 2; minimum = 0x800; }
        else if (first >= 0xf0 && first <= 0xf4) { cp = first & 0x07; continuation = 3; minimum = 0x10000; }
        else throw BridgeError("The local model returned invalid text. Your original is unchanged.");
        for (int j = 0; j < continuation; ++j) {
            if (i >= value.size()) throw BridgeError("The local model returned incomplete text. Your original is unchanged.");
            const auto byte = static_cast<uint8_t>(value[i++]);
            if ((byte & 0xc0) != 0x80) throw BridgeError("The local model returned invalid text. Your original is unchanged.");
            cp = (cp << 6) | (byte & 0x3f);
        }
        if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) || cp == 0) {
            throw BridgeError("The local model returned invalid text. Your original is unchanged.");
        }
        if (cp <= 0xffff) result.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            result.push_back(static_cast<jchar>(0xd800 + (cp >> 10)));
            result.push_back(static_cast<jchar>(0xdc00 + (cp & 0x3ff)));
        }
    }
    return env->NewString(result.data(), static_cast<jsize>(result.size()));
}

// Bounded GGUF v3 header reader before llama.cpp allocates tensors. Imports are
// limited to the tested text-only qwen2 model family, not arbitrary GGUFs.
class HeaderReader {
public:
    HeaderReader(const std::string & path, Session & session) : file(path, std::ios::binary), session(session) {
        if (!file) fail();
        file.seekg(0, std::ios::end);
        const auto end = file.tellg();
        if (end < 24 || static_cast<uint64_t>(end) > 2ULL * 1024 * 1024 * 1024) fail();
        size = static_cast<uint64_t>(end);
        file.seekg(0);
    }
    uint64_t integer(size_t bytes) {
        uint64_t value = 0;
        for (size_t i = 0; i < bytes; ++i) {
            unsigned char byte;
            read(reinterpret_cast<char *>(&byte), 1);
            value |= static_cast<uint64_t>(byte) << (8 * i);
        }
        return value;
    }
    std::string string(size_t limit = 65536) {
        const uint64_t length = integer(8);
        if (length > limit) fail();
        std::string value(static_cast<size_t>(length), '\0');
        if (length != 0) read(value.data(), length);
        if (value.find('\0') != std::string::npos) fail();
        return value;
    }
    void skip_value(uint32_t type) {
        check_stop(session);
        if (type == 8) { const auto length = integer(8); skip(length); return; }
        if (type == 9) {
            const auto element_type = integer(4);
            const auto count = integer(8);
            if (element_type == 9 || element_type > 12 || count > 1000000) fail();
            if (element_type == 8) {
                for (uint64_t i = 0; i < count; ++i) { check_stop(session); skip(integer(8)); }
            } else {
                const size_t width = scalar_width(element_type);
                skip(count * width);
            }
            return;
        }
        skip(scalar_width(type));
    }
    uint64_t position() { const auto pos = file.tellg(); if (pos < 0) fail(); return static_cast<uint64_t>(pos); }
    uint64_t file_size() const { return size; }
    static void fail() { throw BridgeError("This is not a supported, complete Qwen2 GGUF model."); }
private:
    std::ifstream file;
    Session & session;
    uint64_t size = 0;
    static size_t scalar_width(uint32_t type) {
        switch (type) {
            case 0: case 1: case 7: return 1;
            case 2: case 3: return 2;
            case 4: case 5: case 6: return 4;
            case 10: case 11: case 12: return 8;
            default: fail(); return 0;
        }
    }
    void read(char * output, uint64_t length) {
        check_stop(session);
        const uint64_t pos = position();
        if (pos > size || length > size - pos || pos + length > kMaxMetadataBytes) fail();
        file.read(output, static_cast<std::streamsize>(length));
        if (!file) fail();
    }
    void skip(uint64_t length) {
        check_stop(session);
        const uint64_t pos = position();
        if (pos > size || length > size - pos || pos + length > kMaxMetadataBytes) fail();
        file.seekg(static_cast<std::streamoff>(length), std::ios::cur);
        if (!file) fail();
    }
};

void validate_header(const std::string & path, Session & session) {
    HeaderReader reader(path, session);
    if (reader.integer(4) != 0x46554747 || reader.integer(4) != 3) HeaderReader::fail();
    const uint64_t tensors = reader.integer(8);
    const uint64_t pairs = reader.integer(8);
    if (tensors == 0 || tensors > 1024 || pairs == 0 || pairs > 512) HeaderReader::fail();
    bool qwen2 = false;
    bool has_template = false;
    uint64_t alignment = 32;
    for (uint64_t i = 0; i < pairs; ++i) {
        const std::string key = reader.string(256);
        const auto type = static_cast<uint32_t>(reader.integer(4));
        if (key == "general.architecture") {
            if (type != 8 || qwen2) HeaderReader::fail();
            qwen2 = reader.string(64) == "qwen2";
            if (!qwen2) HeaderReader::fail();
        } else if (key == "tokenizer.chat_template") {
            if (type != 8 || has_template) HeaderReader::fail();
            const auto value = reader.string();
            has_template = !value.empty();
        } else if (key == "general.alignment") {
            if (type != 4) HeaderReader::fail();
            alignment = reader.integer(4);
            if (alignment == 0 || alignment > 4096 || (alignment & (alignment - 1)) != 0) HeaderReader::fail();
        } else if (key == "split.count") {
            if (type != 2 || reader.integer(2) != 1) HeaderReader::fail();
        } else reader.skip_value(type);
    }
    if (!qwen2 || !has_template) HeaderReader::fail();
    std::vector<std::pair<uint64_t, uint64_t>> ranges;
    ranges.reserve(tensors);
    for (uint64_t i = 0; i < tensors; ++i) {
        if (reader.string(256).empty()) HeaderReader::fail();
        const auto dims = reader.integer(4);
        if (dims == 0 || dims > 4) HeaderReader::fail();
        uint64_t elements = 1;
        uint64_t first_dim = 0;
        for (uint64_t j = 0; j < dims; ++j) {
            const auto dim = reader.integer(8);
            if (dim == 0 || dim > 1000000 || elements > 1000000000 / dim) HeaderReader::fail();
            elements *= dim;
            if (j == 0) first_dim = dim;
        }
        const auto type = reader.integer(4);
        // The supplied Q4_K_M model uses these tensor types. Expanding model
        // support requires testing, rather than passing unknown types onward.
        if (type != GGML_TYPE_F32 && type != GGML_TYPE_F16 && type != GGML_TYPE_Q4_K && type != GGML_TYPE_Q6_K) HeaderReader::fail();
        const auto ggml_type = static_cast<enum ggml_type>(type);
        const auto block = static_cast<uint64_t>(ggml_blck_size(ggml_type));
        if (first_dim % block != 0) HeaderReader::fail();
        const uint64_t bytes = (elements / block) * ggml_type_size(ggml_type);
        const uint64_t offset = reader.integer(8);
        if (offset % alignment != 0 || offset > reader.file_size() || bytes > reader.file_size() - offset) HeaderReader::fail();
        ranges.emplace_back(offset, bytes);
    }
    const uint64_t position = reader.position();
    const uint64_t data_start = (position + alignment - 1) & ~(alignment - 1);
    if (data_start > reader.file_size()) HeaderReader::fail();
    for (const auto & range : ranges) {
        if (range.first > reader.file_size() - data_start || range.second > reader.file_size() - data_start - range.first) HeaderReader::fail();
    }
}

std::string run(Session & session, const std::string & path, const std::string & instructions,
                const std::string & input, int context_tokens, int threads, int max_output) {
    if (path.empty() || instructions.empty() || input.empty()) throw BridgeError("Missing local editing input.");
    if (context_tokens < 512 || context_tokens > 8192 || threads < 1 || threads > 8 || max_output < 256 || max_output > 2048) {
        throw BridgeError("The local model settings are outside the tested limits.");
    }
    std::unique_lock<std::timed_mutex> lock(inference_mutex, std::defer_lock);
    while (!lock.try_lock_for(std::chrono::milliseconds(25))) check_stop(session);
    check_stop(session);
    std::call_once(backend_once, [] {
        // Both logging hooks can otherwise expose metadata or generated text.
        llama_log_set(discard_log, nullptr);
        ggml_log_set(discard_log, nullptr);
        llama_backend_init();
    });
    validate_header(path, session);
    check_stop(session);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    model_params.progress_callback = load_progress;
    model_params.progress_callback_user_data = &session;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        llama_model_load_from_file(path.c_str(), model_params), llama_model_free);
    check_stop(session);
    if (!model) throw BridgeError("The local model could not load. Check its file and available memory.");
    if (llama_model_n_ctx_train(model.get()) < context_tokens || !llama_model_has_decoder(model.get()) || llama_model_has_encoder(model.get())) {
        throw BridgeError("This model does not support the tested text editing settings.");
    }
    const llama_vocab * vocab = llama_model_get_vocab(model.get());
    const char * chat_template = llama_model_chat_template(model.get(), nullptr);
    if (vocab == nullptr || chat_template == nullptr || *chat_template == '\0') throw BridgeError("This model is missing its chat template.");
    const llama_chat_message messages[] = {{"system", instructions.c_str()}, {"user", input.c_str()}};
    int32_t prompt_bytes = llama_chat_apply_template(chat_template, messages, 2, true, nullptr, 0);
    if (prompt_bytes <= 0 || prompt_bytes > 1024 * 1024) throw BridgeError("This model's chat template is not supported.");
    std::string prompt(static_cast<size_t>(prompt_bytes), '\0');
    const int32_t formatted = llama_chat_apply_template(chat_template, messages, 2, true, prompt.data(), prompt_bytes);
    if (formatted != prompt_bytes) throw BridgeError("This model's chat template could not format the draft.");
    check_stop(session);
    const int32_t needed = llama_tokenize(vocab, prompt.data(), prompt_bytes, nullptr, 0, true, true);
    if (needed == INT32_MIN || needed >= 0 || -needed > context_tokens - 256) {
        throw BridgeError("This draft is too long for the local model. Your original is unchanged.");
    }
    std::vector<llama_token> tokens(static_cast<size_t>(-needed));
    const int32_t token_count = llama_tokenize(vocab, prompt.data(), prompt_bytes, tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (token_count <= 0 || token_count != -needed) throw BridgeError("The local model could not read this draft.");
    const int output_budget = std::min(max_output, context_tokens - token_count);

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_tokens);
    context_params.n_batch = 128;
    context_params.n_ubatch = 128;
    context_params.n_seq_max = 1;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.no_perf = true;
    context_params.offload_kqv = false;
    context_params.op_offload = false;
    context_params.abort_callback = abort_decode;
    context_params.abort_callback_data = &session;
    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        llama_init_from_model(model.get(), context_params), llama_free);
    check_stop(session);
    if (!context) throw BridgeError("The local model could not allocate memory. Close other apps or use Raw.");
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    if (!sampler) throw BridgeError("The local model could not start editing.");

    // Small prompt batches permit cancellation and keep peak CPU workspace low.
    for (size_t start = 0; start < tokens.size(); start += 128) {
        check_stop(session);
        const int32_t count = static_cast<int32_t>(std::min<size_t>(128, tokens.size() - start));
        const int32_t result = llama_decode(context.get(), llama_batch_get_one(tokens.data() + start, count));
        check_stop(session);
        if (result != 0) throw BridgeError("The local model could not process this draft. Your original is unchanged.");
    }
    std::string output;
    for (int generated = 0; generated <= output_budget; ++generated) {
        check_stop(session);
        const llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (token < 0) throw BridgeError("The local model did not return valid text.");
        if (llama_vocab_is_eog(vocab, token)) {
            if (output.empty() || output.find_first_not_of(" \t\r\n") == std::string::npos) throw BridgeError("The local model returned no edited text. Your original is unchanged.");
            return output;
        }
        if (generated == output_budget) throw BridgeError("The local model reached its output limit. Your original is unchanged.");
        if (llama_vocab_is_control(vocab, token)) throw BridgeError("The local model returned unsupported text. Your original is unchanged.");
        char piece[256];
        int32_t length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (length < 0) {
            if (length == INT32_MIN || -length > static_cast<int32_t>(kMaxOutputBytes)) throw BridgeError("The local model returned unsupported text.");
            std::string larger(static_cast<size_t>(-length), '\0');
            const int32_t copied = llama_token_to_piece(vocab, token, larger.data(), static_cast<int32_t>(larger.size()), 0, false);
            if (copied != -length || output.size() + larger.size() > kMaxOutputBytes) throw BridgeError("The local model result is too long. Your original is unchanged.");
            output += larger;
        } else {
            if (output.size() + static_cast<size_t>(length) > kMaxOutputBytes) throw BridgeError("The local model result is too long. Your original is unchanged.");
            output.append(piece, static_cast<size_t>(length));
        }
        llama_token next = token;
        const int32_t result = llama_decode(context.get(), llama_batch_get_one(&next, 1));
        check_stop(session);
        if (result != 0) throw BridgeError("The local model could not finish editing. Your original is unchanged.");
    }
    throw BridgeError("The local model did not finish editing. Your original is unchanged.");
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_uk_co_simplyben_rawplus_LlamaNative_nativeCreateSession(JNIEnv * env, jobject) {
    try {
        auto session = std::make_shared<Session>();
        std::lock_guard<std::mutex> lock(sessions_mutex);
        if (next_session == std::numeric_limits<jlong>::max()) throw BridgeError("The local model could not start a session.");
        const jlong id = next_session++;
        sessions.emplace(id, std::move(session));
        return id;
    } catch (...) {
        throw_java(env, "The local model could not start a session.");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_uk_co_simplyben_rawplus_LlamaNative_nativeGenerate(
        JNIEnv * env, jobject, jlong id, jstring path, jstring instructions, jstring input,
        jint context_tokens, jint threads, jint max_output, jlong timeout_ms) {
    const auto session = find_session(id);
    if (!session) { throw_java(env, "The local editing session has ended."); return nullptr; }
    bool expected = false;
    if (!session->running.compare_exchange_strong(expected, true)) {
        throw_java(env, "A local edit is already running in this session."); return nullptr;
    }
    struct RunningGuard { Session & s; ~RunningGuard() { s.running.store(false); } } running{*session};
    try {
        if (timeout_ms < 1000 || timeout_ms > 10 * 60 * 1000) throw BridgeError("The local editing deadline is outside the tested limits.");
        session->deadline = Clock::now() + std::chrono::milliseconds(timeout_ms);
        check_stop(*session);
        const auto native_path = java_utf8(env, path, 4096);
        const auto native_instructions = java_utf8(env, instructions, 65536);
        const auto native_input = java_utf8(env, input, 262144);
        const auto result = run(*session, native_path, native_instructions, native_input, context_tokens, threads, max_output);
        check_stop(*session);
        return java_string(env, result);
    } catch (const std::bad_alloc &) {
        throw_java(env, "The local model ran out of memory. Your original is unchanged.");
    } catch (const BridgeError & error) {
        // All bridge error strings are fixed messages, never model/user text.
        throw_java(env, error.what());
    } catch (...) {
        // Third-party exceptions may contain user text or file metadata.
        throw_java(env, "Local editing failed. Your original is unchanged.");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_uk_co_simplyben_rawplus_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong id) {
    const auto session = find_session(id);
    if (session) session->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_co_simplyben_rawplus_LlamaNative_nativeDestroySession(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    const auto it = sessions.find(id);
    if (it != sessions.end()) {
        it->second->cancelled.store(true, std::memory_order_relaxed);
        sessions.erase(it);
    }
}
