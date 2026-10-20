package uk.co.simplyben.rawplus

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One explicit edit using this app's ChatGPT grant. Never uses the saved API key. */
class ChatGptEditClient(context: Context, private val skill: () -> String) {
    private val auth = ChatGptAuth(context.applicationContext)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private class Call {
        @Volatile var cancelled = false
        var connection: HttpURLConnection? = null
        fun checkActive() { check(!cancelled && !Thread.currentThread().isInterrupted) { "ChatGPT editing cancelled." } }
    }
    private var current: Call? = null
    private var future: Future<*>? = null
    private var deadline: Runnable? = null
    private var generation = 0L
    private var closed = false
    var acceptedModel: String? = null
        private set

    fun edit(request: EditRequest, callback: (Result<String>) -> Unit) {
        cancel()
        if (closed) {
            callback(Result.failure(IllegalStateException("ChatGPT editing is closed. Reopen the keyboard.")))
            return
        }
        val run = generation
        val call = Call()
        synchronized(lock) { current = call }
        deadline = Runnable {
            if (!closed && run == generation) {
                cancel()
                callback(Result.failure(IllegalStateException("ChatGPT editing timed out. Your original is safe. Use Raw or retry.")))
            }
        }.also { main.postDelayed(it, 90_000L) }
        future = worker.submit {
            var access: ChatGptAccess? = null
            val result = runCatching {
                call.checkActive()
                val selected = auth.access { call.cancelled || Thread.currentThread().isInterrupted }
                access = selected
                call.checkActive()
                check(auth.isCurrent(selected)) { "ChatGPT account or model changed. Your original is safe. Retry when ready." }
                val payload = ChatGptProtocol.body(request, skill(), selected.model).toByteArray(Charsets.UTF_8)
                val connection = (URL(ChatGptProtocol.ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer ${selected.token}")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream")
                    setFixedLengthStreamingMode(payload.size)
                }
                synchronized(lock) { call.checkActive(); call.connection = connection }
                connection.outputStream.use { call.checkActive(); it.write(payload) }
                call.checkActive()
                val status = connection.responseCode
                if (status != 200) {
                    val problem = connection.errorStream?.use { boundedText(it, 8192) }.orEmpty()
                    error(ChatGptProtocol.httpError(status, problem))
                }
                check(connection.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", true) == true) {
                    "ChatGPT did not return the expected stream. Your original is safe. Use Raw or retry."
                }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val text = connection.inputStream.use { stream ->
                    InputStreamReader(BoundedInput(stream, 4_194_304), decoder).use { reader ->
                        ChatGptProtocol.readStream(reader) { call.cancelled || Thread.currentThread().isInterrupted }
                    }
                }
                call.checkActive()
                check(auth.isCurrent(selected)) { "ChatGPT account or model changed. Your original is safe. Retry when ready." }
                text
            }.recoverCatching { failure ->
                if (failure is ChatGptAuthFailure || failure is IllegalArgumentException || failure is IllegalStateException) throw failure
                error("Cannot reach ChatGPT. Your original is safe. Check your connection and retry, or use Raw.")
            }
            synchronized(lock) {
                call.connection?.disconnect()
                call.connection = null
                if (current === call) current = null
            }
            main.post {
                if (!closed && run == generation) {
                    deadline?.let(main::removeCallbacks)
                    deadline = null
                    val selected = access
                    if (result.isSuccess && selected != null && auth.isCurrent(selected)) {
                        acceptedModel = selected.model
                        callback(result)
                    } else if (result.isSuccess) callback(Result.failure(IllegalStateException(
                        "ChatGPT account or model changed. Your original is safe.")))
                    else callback(result)
                }
            }
        }
    }

    fun cancel() {
        generation++
        acceptedModel = null
        deadline?.let(main::removeCallbacks)
        deadline = null
        synchronized(lock) {
            current?.let { it.cancelled = true; it.connection?.disconnect() }
            current = null
        }
        auth.cancel()
        future?.cancel(true)
        future = null
    }

    fun close() {
        if (closed) return
        closed = true
        cancel()
        auth.close()
        worker.shutdownNow()
    }

    private fun boundedText(input: InputStream, limit: Int): String {
        val buffer = ByteArrayOutputStream()
        val bytes = ByteArray(1024)
        while (buffer.size() < limit) {
            val read = input.read(bytes, 0, minOf(bytes.size, limit - buffer.size()))
            if (read == -1) break
            buffer.write(bytes, 0, read)
        }
        return buffer.toString("UTF-8")
    }

    private class BoundedInput(private val input: InputStream, private val limit: Int) : InputStream() {
        private var count = 0
        override fun read(): Int = input.read().also { if (it != -1) add(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            input.read(buffer, offset, length).also { if (it > 0) add(it) }
        private fun add(bytes: Int) {
            check(bytes <= limit - count) { "ChatGPT's response exceeded the limit. Your original is safe. Use Raw." }
            count += bytes
        }
        override fun close() = input.close()
    }
}
