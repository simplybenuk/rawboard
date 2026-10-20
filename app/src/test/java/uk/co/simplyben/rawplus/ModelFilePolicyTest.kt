package uk.co.simplyben.rawplus

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ModelFilePolicyTest {
    private val valid = byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0, 42, 43, 44, 45)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun `pinned download identifies a single model revision and size`() {
        assertEquals(1_117_320_736L, ModelFilePolicy.FILE_BYTES)
        assertEquals(64, ModelFilePolicy.SHA256.length)
        assertTrue(ModelFilePolicy.DOWNLOAD_URL.startsWith("https://huggingface.co/Qwen/"))
        assertTrue(ModelFilePolicy.DOWNLOAD_URL.contains("/resolve/${ModelFilePolicy.REVISION}/"))
        assertTrue(ModelFilePolicy.DOWNLOAD_URL.endsWith(ModelFilePolicy.FILENAME))
    }

    @Test fun `matching model bytes pass through unchanged with progress`() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        ModelFilePolicy.copyVerified(ByteArrayInputStream(valid), output, valid.size.toLong(), hash(valid), progress = { progress.add(it) })
        assertArrayEquals(valid, output.toByteArray())
        assertEquals(valid.size.toLong(), progress.last())
    }

    @Test fun `header verifies when reads split across individual bytes`() {
        val input = object : InputStream() {
            private var offset = 0
            override fun read() = if (offset < valid.size) valid[offset++].toInt() and 255 else -1
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (offset == valid.size) return -1
                buffer[off] = valid[offset++]
                return 1
            }
        }
        val output = ByteArrayOutputStream()
        ModelFilePolicy.copyVerified(input, output, valid.size.toLong(), hash(valid))
        assertArrayEquals(valid, output.toByteArray())
    }

    @Test fun `truncated extra or changed bytes cannot pass verification`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelFilePolicy.copyVerified(ByteArrayInputStream(valid.copyOf(10)), ByteArrayOutputStream(), valid.size.toLong(), hash(valid))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelFilePolicy.copyVerified(ByteArrayInputStream(valid + 0), ByteArrayOutputStream(), valid.size.toLong(), hash(valid))
        }
        val modified = valid.copyOf().apply { this[lastIndex] = 0 }
        assertThrows(IllegalArgumentException::class.java) {
            ModelFilePolicy.copyVerified(ByteArrayInputStream(modified), ByteArrayOutputStream(), valid.size.toLong(), hash(valid))
        }
    }

    @Test fun `invalid magic and version are refused even with matching hash`() {
        for (index in listOf(0, 4, 5, 6, 7)) {
            val wrongHeader = valid.copyOf().apply { this[index] = 9 }
            assertThrows(IllegalArgumentException::class.java) {
                ModelFilePolicy.copyVerified(ByteArrayInputStream(wrongHeader), ByteArrayOutputStream(), wrongHeader.size.toLong(), hash(wrongHeader))
            }
        }
    }

    @Test fun `cancelled model setup never reads or writes further bytes`() {
        val output = ByteArrayOutputStream()
        assertThrows(InterruptedIOException::class.java) {
            ModelFilePolicy.copyVerified(ByteArrayInputStream(valid), output, valid.size.toLong(), hash(valid), cancelled = { true })
        }
        assertEquals(0, output.size())
    }
}
