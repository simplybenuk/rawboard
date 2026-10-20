package uk.co.simplyben.rawplus

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Explicit foreground setup only. Models live outside backups and replace the installed file only after verification. */
class LocalModelStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "local-model")
    private val installed = File(directory, "model.gguf")
    private val fingerprint = File(directory, "verified.sha256")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private class SetupFailure(message: String) : IOException(message)
    private val lock = Any()
    private var active: Operation? = null
    private var closed = false

    private class Operation {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var input: Closeable? = null
        val signal = CancellationSignal()
        var future: Future<*>? = null
        fun cancel() {
            cancelled = true
            future?.cancel(true)
            try { signal.cancel() } catch (_: Exception) { }
            try { connection?.disconnect() } catch (_: Exception) { }
            try { input?.close() } catch (_: Exception) { }
        }
    }

    fun modelFile(): File? = if (installed.isFile && installed.length() == FILE_BYTES &&
        fingerprint.isFile && fingerprint.length() == SHA256.length.toLong() &&
        runCatching { fingerprint.readText() == SHA256 }.getOrDefault(false)) installed else null

    fun statusText(): String = if (modelFile() != null) "$NAME is installed and was verified. Ready for a local test."
        else "No verified model installed. Download or import the model below. Raw still works."

    fun download(progress: (Long, Long) -> Unit, done: (Result<Unit>) -> Unit) = start(done) { operation ->
        prepareSpace()
        var next = URL(ModelFilePolicy.DOWNLOAD_URL)
        var stream: InputStream? = null
        for (redirect in 0..MAX_REDIRECTS) {
            checkActive(operation)
            checkHttps(next)
            val connection = (next.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity")
            }
            operation.connection = connection
            checkActive(operation)
            val code = connection.responseCode
            checkActive(operation)
            if (code in REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                checkSetup(redirect < MAX_REDIRECTS && !location.isNullOrBlank()) { "The download redirected too many times. Try the browser download and import instead." }
                next = URL(next, location)
                checkHttps(next)
                continue
            }
            checkSetup(code == HttpURLConnection.HTTP_OK) { "The model download failed. Try again or download in your browser and import." }
            val reportedBytes = connection.contentLengthLong
            checkSetup(reportedBytes == -1L || reportedBytes == FILE_BYTES) { "The server did not return the expected model file." }
            checkActive(operation)
            stream = connection.inputStream
            operation.input = stream
            checkActive(operation)
            break
        }
        val input = stream ?: throw SetupFailure("Could not open the model download.")
        operation.input = input
        checkActive(operation)
        input.use { install(it, operation, progress) }
    }

    fun importModel(uri: Uri, progress: (Long, Long) -> Unit, done: (Result<Unit>) -> Unit) = start(done) { operation ->
        prepareSpace()
        checkSetup(uri.scheme == "content") { "Choose a model with the Android file picker." }
        checkActive(operation)
        val descriptor = app.contentResolver.openAssetFileDescriptor(uri, "r", operation.signal)
            ?: throw SetupFailure("Could not open the selected file.")
        operation.input = descriptor
        descriptor.use {
            checkActive(operation)
            val input = descriptor.createInputStream()
            operation.input = input
            checkActive(operation)
            input.use { install(it, operation, progress) }
        }
    }

    fun delete(done: (Result<Unit>) -> Unit) = start(done) { operation ->
        synchronized(lock) {
            checkActive(operation)
            checkSetup(!installed.exists() || installed.delete()) { "Could not remove the local model. Try again." }
            checkSetup(!fingerprint.exists() || fingerprint.delete()) { "Could not remove the model verification record. Try again." }
        }
    }

    private fun install(input: InputStream, operation: Operation, progress: (Long, Long) -> Unit) {
        val staging = createPart("model-")
        var marker: File? = null
        try {
            var lastProgressAt = 0L
            FileOutputStream(staging).use { output ->
                ModelFilePolicy.copyVerified(input, output, cancelled = { !isActive(operation) }) { bytes ->
                    val now = System.nanoTime()
                    if (bytes == FILE_BYTES || now - lastProgressAt >= 200_000_000L) {
                        lastProgressAt = now
                        main.post { if (isActive(operation)) progress(bytes, FILE_BYTES) }
                    }
                }
                output.fd.sync()
            }
            val verification = createPart("verified-").also { marker = it }
            FileOutputStream(verification).use { output ->
                output.write(SHA256.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            synchronized(lock) {
                checkActive(operation)
                // Both replacements are atomic on the same private filesystem. Every valid file has this same pinned hash.
                Os.rename(verification.absolutePath, fingerprint.absolutePath)
                Os.rename(staging.absolutePath, installed.absolutePath)
            }
        } finally {
            synchronized(PART_LOCK) {
                staging.delete()
                marker?.delete()
                ACTIVE_PARTS.remove(staging.absolutePath)
                marker?.let { ACTIVE_PARTS.remove(it.absolutePath) }
            }
        }
    }

    private fun createPart(prefix: String): File = synchronized(PART_LOCK) {
        File.createTempFile(prefix, ".part", directory).also { ACTIVE_PARTS.add(it.absolutePath) }
    }

    private fun prepareSpace() {
        checkSetup(directory.isDirectory || directory.mkdirs()) { "Could not create private model storage." }
        synchronized(PART_LOCK) {
            // Clean interrupted setup after process death, without touching a still-closing operation.
            directory.listFiles()?.filter { it.name.endsWith(".part") &&
                (it.name.startsWith("model-") || it.name.startsWith("verified-")) &&
                it.absolutePath !in ACTIVE_PARTS }?.forEach { it.delete() }
        }
        checkSetup(StatFs(directory.absolutePath).availableBytes >= FILE_BYTES + 64 * 1024 * 1024L) {
            "Free at least 1.2 GB of storage before installing the model. An existing model stays available until a replacement is verified."
        }
    }

    private inline fun checkSetup(condition: Boolean, message: () -> String) {
        if (!condition) throw SetupFailure(message())
    }

    private fun checkHttps(url: URL) {
        val uri = URI(url.toString())
        checkSetup(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) {
            "The model download requires a secure HTTPS connection."
        }
    }

    private fun start(done: (Result<Unit>) -> Unit, action: (Operation) -> Unit) {
        cancel()
        val operation = Operation()
        synchronized(lock) {
            checkSetup(!closed) { "Model setup is closed." }
            active = operation
            operation.future = worker.submit {
                val result = runCatching { checkActive(operation); action(operation) }.recoverCatching { failure ->
                    if (failure is InterruptedIOException || operation.cancelled) throw InterruptedIOException("Model setup cancelled.")
                    // Platform exceptions can contain document paths and signed download URLs. Keep them out of UI and logs.
                    if (failure is ModelFileException || failure is SetupFailure) throw failure
                    throw IOException("Model setup failed. Check your connection and storage, then try again. Your existing model is unchanged.")
                }
                try { operation.connection?.disconnect() } catch (_: Exception) { }
                try { operation.input?.close() } catch (_: Exception) { }
                main.post {
                    if (isActive(operation)) {
                        synchronized(lock) { if (active === operation) active = null }
                        done(result)
                    }
                }
            }
        }
    }

    private fun isActive(operation: Operation) = synchronized(lock) { !closed && active === operation && !operation.cancelled }
    private fun checkActive(operation: Operation) {
        if (!isActive(operation) || Thread.currentThread().isInterrupted) throw InterruptedIOException("Model setup cancelled.")
    }

    fun cancel() {
        val previous = synchronized(lock) { active.also { active = null } }
        previous?.cancel()
    }

    fun close() {
        synchronized(lock) { closed = true }
        cancel()
        worker.shutdownNow()
    }

    companion object {
        const val NAME = ModelFilePolicy.NAME
        const val FILE_BYTES = ModelFilePolicy.FILE_BYTES
        const val SHA256 = ModelFilePolicy.SHA256
        private val PART_LOCK = Any()
        private val ACTIVE_PARTS = mutableSetOf<String>()
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
