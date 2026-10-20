package uk.co.simplyben.rawplus

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** App-owned CPU inference. No AICore service, HTTP client, credentials or cloud fallback. */
class LlamaEditor(context: Context, private val skill: () -> String) {
    private val models = LocalModelStore(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private class Call {
        var cancelled = false
        var session = 0L
    }
    private var current: Call? = null
    private var future: Future<*>? = null
    private var deadline: Runnable? = null
    private var generation = 0L
    private var closed = false

    fun edit(request: EditRequest, callback: (Result<String>) -> Unit) {
        cancel()
        if (closed) {
            callback(Result.failure(IllegalStateException("Local editing is closed. Reopen the keyboard.")))
            return
        }
        val run = generation
        val call = Call()
        synchronized(lock) { current = call }
        deadline = Runnable {
            if (run == generation && !closed) {
                cancel()
                callback(Result.failure(IllegalStateException(
                    "Local editing timed out. Your original is safe. Use Raw or retry.")))
            }
        }.also { main.postDelayed(it, LlamaProtocol.TIMEOUT_MS + 5_000L) }
        future = executor.submit {
            val result = try {
                val prompt = LlamaProtocol.prompt(request, skill())
                val model = models.modelFile() ?: error(
                    "Set up the local model in Providers first. Your original is safe. Use Raw.")
                synchronized(lock) {
                    check(!call.cancelled && current === call) { "Local editing cancelled." }
                    // Loading the library happens only after an explicit AI-edit or sample tap.
                    call.session = LlamaNative.createSession()
                    check(call.session != 0L) { "Could not start local editing. Use Raw." }
                }
                val output = LlamaNative.generate(call.session, model.absolutePath,
                    prompt.instructions, prompt.input, LlamaProtocol.CONTEXT_TOKENS,
                    Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    LlamaProtocol.MAX_OUTPUT_TOKENS, LlamaProtocol.TIMEOUT_MS)
                Result.success(LlamaProtocol.response(request, output))
            } catch (_: LinkageError) {
                Result.failure(IllegalStateException("The local engine could not load on this device. Your original is safe. Use Raw."))
            } catch (_: OutOfMemoryError) {
                Result.failure(IllegalStateException("There is not enough free memory for local editing. Your original is safe. Use Raw."))
            } catch (failure: Exception) {
                Result.failure(if (failure is IllegalArgumentException || failure is IllegalStateException) failure
                    else IllegalStateException("Local editing failed. Your original is safe. Use Raw or retry."))
            } finally {
                synchronized(lock) {
                    // cancel() uses the same lock, so it cannot touch an already freed handle.
                    if (call.session != 0L) {
                        LlamaNative.destroySession(call.session)
                        call.session = 0L
                    }
                    if (current === call) current = null
                }
            }
            main.post {
                if (!closed && run == generation) {
                    deadline?.let(main::removeCallbacks)
                    deadline = null
                    callback(result)
                }
            }
        }
    }

    fun cancel() {
        generation++
        deadline?.let(main::removeCallbacks)
        deadline = null
        synchronized(lock) {
            current?.let { call ->
                call.cancelled = true
                if (call.session != 0L) LlamaNative.cancel(call.session)
            }
            current = null
        }
        future?.cancel(true)
        future = null
    }

    fun close() {
        if (closed) return
        closed = true
        cancel()
        executor.shutdownNow()
        models.close()
    }
}
