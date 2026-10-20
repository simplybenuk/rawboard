package uk.co.simplyben.rawplus

import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest

/** The prototype accepts one pinned model, including imports. No unverified model reaches inference. */
internal class ModelFileException(message: String) : IllegalArgumentException(message)

internal object ModelFilePolicy {
    const val NAME = "Qwen2.5-1.5B-Instruct · Q4_K_M"
    const val FILE_BYTES = 1_117_320_736L
    const val SHA256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
    const val REVISION = "91cad51170dc346986eccefdc2dd33a9da36ead9"
    const val FILENAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val DOWNLOAD_URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/$REVISION/$FILENAME"
    const val MODEL_PAGE = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/$REVISION/$FILENAME"

    fun verifyHeader(header: ByteArray) {
        requireModel(header.size >= 8 && header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() &&
            header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4] == 3.toByte() && header[5] == 0.toByte() && header[6] == 0.toByte() && header[7] == 0.toByte()) {
            "This is not the expected GGUF version 3 model."
        }
    }

    private inline fun requireModel(condition: Boolean, message: () -> String) {
        if (!condition) throw ModelFileException(message())
    }

    /** Stream, bound and hash the file without keeping model bytes in memory. Streams remain caller-owned. */
    fun copyVerified(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long = FILE_BYTES,
        expectedSha256: String = SHA256,
        cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {}
    ) {
        require(expectedBytes >= 8)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val header = ByteArray(8)
        var count = 0L
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Model setup cancelled.")
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            requireModel(read.toLong() <= expectedBytes - count) { "The file is larger than the expected model. Nothing was installed." }
            val headerCount = minOf(read, (8 - count).coerceAtLeast(0).toInt())
            if (headerCount > 0) buffer.copyInto(header, count.toInt(), 0, headerCount)
            if (count < 8 && count + read >= 8) verifyHeader(header)
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            count += read
            progress(count)
        }
        if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Model setup cancelled.")
        requireModel(count == expectedBytes) { "The model file is incomplete. Nothing was installed." }
        verifyHeader(header)
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        requireModel(actualHash == expectedSha256) { "The model checksum did not match. Nothing was installed." }
    }
}
