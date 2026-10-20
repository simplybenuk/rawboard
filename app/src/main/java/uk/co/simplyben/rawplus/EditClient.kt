package uk.co.simplyben.rawplus

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Text editing transport, deliberately independent of speech recognition and host text. */
class EditClient(private val skill: () -> String) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private class Call {
        @Volatile var cancelled = false
        var connection: HttpURLConnection? = null
        fun checkActive() { check(!cancelled && !Thread.currentThread().isInterrupted) { "Editing cancelled." } }
    }
    private var current: Call? = null
    private var future: Future<*>? = null
    private var deadline: Runnable? = null
    private var generation = 0L

    fun edit(config: EditingConfig, request: EditRequest, callback: (Result<String>) -> Unit) {
        cancel()
        if (!config.provider.external) {
            callback(Result.failure(IllegalArgumentException("On-device editing cannot use the network client.")))
            return
        }
        val run = generation
        val call = Call()
        synchronized(lock) { current = call }
        deadline = Runnable {
            if (run == generation) {
                cancel()
                callback(Result.failure(IllegalStateException("Editing timed out. Your original is safe. Retry or use Raw.")))
            }
        }.also { main.postDelayed(it, 45_000L) }
        future = executor.submit {
            var http: HttpURLConnection? = null
            val result = runCatching {
                call.checkActive()
                val endpoint = EditProtocol.endpoint(config)
                http = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 35_000
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer ${config.credential}")
                }
                synchronized(lock) {
                    call.checkActive()
                    call.connection = http
                }
                val payload = EditProtocol.body(config, request,
                    if (config.provider == EditingProvider.OPENAI) skill() else "").toByteArray(Charsets.UTF_8)
                call.checkActive()
                http!!.setFixedLengthStreamingMode(payload.size)
                http!!.outputStream.use { output ->
                    // Cancellation disconnects a registered connection; already sent bytes cannot be recalled.
                    call.checkActive()
                    output.write(payload)
                }
                val status = http!!.responseCode
                if (status != 200) {
                    val problem = http!!.errorStream?.use { readBounded(it, 8192) }.orEmpty()
                    error(EditProtocol.error(config, status, problem))
                }
                val response = http!!.inputStream.use { readBounded(it, 65_536) }
                EditProtocol.response(config, response)
            }.recoverCatching { failure ->
                if (failure is IllegalStateException || failure is IllegalArgumentException) throw failure
                error("Cannot reach the editing service. Your original is safe. Retry or use Raw.")
            }
            http?.disconnect()
            synchronized(lock) { call.connection = null; if (current === call) current = null }
            main.post {
                if (run == generation) {
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
        val connection = synchronized(lock) {
            val call = current
            call?.cancelled = true
            current = null
            call?.connection
        }
        future?.cancel(true)
        connection?.disconnect()
        future = null
    }

    fun close() { cancel(); executor.shutdownNow() }

    private fun readBounded(input: java.io.InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        check(output.size() <= limit) { "Editing response was too large. Your original is safe. Use Raw or retry." }
        return output.toString(Charsets.UTF_8.name())
    }
}
