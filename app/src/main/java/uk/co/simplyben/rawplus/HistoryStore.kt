package uk.co.simplyben.rawplus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val sessionId: String,
    val event: String,
    val original: String,
    val mode: EditMode,
    val text: String,
    val promptVersion: String
)

/** Pure journal rules. Retained entries are immutable source/output pairs. */
internal object HistoryJournal {
    const val MAX_ENTRIES = 100
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_TEXT = 65_536
    const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    private val events = setOf("dictated", "edited", "inserted")

    fun validate(entry: HistoryEntry) {
        require(entry.id.length in 1..128 && entry.sessionId.length in 1..128)
        require(entry.timestamp > 0 && entry.event in events)
        require(entry.original.isNotBlank() && entry.original.length <= Draft.MAX_TRANSCRIPT)
        require(entry.text.isNotBlank() && entry.text.length <= MAX_TEXT)
        require(entry.promptVersion.length in 1..128)
    }

    fun retain(entries: List<HistoryEntry>, now: Long): List<HistoryEntry> {
        val kept = entries.filter { it.timestamp >= now - MAX_AGE_MILLIS }
            .takeLast(MAX_ENTRIES).toMutableList()
        while (kept.isNotEmpty() && encode(kept).toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            kept.removeAt(0)
        }
        return kept
    }

    fun append(entries: List<HistoryEntry>, entry: HistoryEntry, now: Long): List<HistoryEntry> {
        validate(entry)
        val previous = entries.lastOrNull()
        val base = if (entry.event == "dictated" && entry.mode == EditMode.RAW &&
            previous?.event == "dictated" && previous.mode == EditMode.RAW &&
            previous.sessionId == entry.sessionId) entries.dropLast(1) else entries
        return retain(base + entry, now)
    }

    fun encode(entries: List<HistoryEntry>): String = JSONObject()
        .put("schemaVersion", 1)
        .put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject()
                    .put("id", entry.id)
                    .put("timestamp", entry.timestamp)
                    .put("sessionId", entry.sessionId)
                    .put("event", entry.event)
                    .put("original", entry.original)
                    .put("mode", entry.mode.wireName)
                    .put("text", entry.text)
                    .put("promptVersion", entry.promptVersion))
            }
        }).toString()

    fun decode(json: String): List<HistoryEntry> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val document = JSONObject(json)
        require(document.get("schemaVersion") == 1)
        val entries = document.getJSONArray("entries")
        require(entries.length() <= MAX_ENTRIES)
        return List(entries.length()) { index ->
            val value = entries.getJSONObject(index)
            fun string(name: String): String = value.get(name) as? String
                ?: throw IllegalArgumentException("Invalid history field")
            val timestamp = value.get("timestamp")
            require(timestamp is Long || timestamp is Int)
            HistoryEntry(
                string("id"), (timestamp as Number).toLong(), string("sessionId"), string("event"),
                string("original"), EditMode.entries.first { it.wireName == string("mode") },
                string("text"), string("promptVersion")
            ).also(::validate)
        }
    }
}

/** Local test history only. No audio, credentials or host-app content enter this store. */
class HistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("local_test_history", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(context.noBackupFilesDir, "test-history.enc"))

    @Volatile var lastError: String? = null
        private set

    /** Capture with an event so a later clear or logging change invalidates queued work. */
    val generation: Long get() = preferences.getLong("generation", 0L)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) = synchronized(LOCK) {
            if (enabled == value) return@synchronized
            check(preferences.edit().putLong("generation", Math.addExact(generation, 1L))
                .putBoolean("enabled", value).commit()) {
                "Could not change local history settings."
            }
        }

    /** A disabled store deliberately skips events and reports success. Never throws to the keyboard. */
    fun record(
        sessionId: String,
        event: String,
        original: String,
        mode: EditMode,
        text: String,
        promptVersion: String,
        expectedGeneration: Long? = null
    ): Boolean = synchronized(LOCK) {
        lastError = null
        try {
            if (expectedGeneration != null && expectedGeneration != generation) return@synchronized true
            if (!enabled) return@synchronized true
            val now = System.currentTimeMillis()
            val entry = HistoryEntry(UUID.randomUUID().toString(), now, sessionId, event,
                original, mode, text, promptVersion)
            HistoryJournal.validate(entry)
            val entries = HistoryJournal.append(load(), entry, now)
            check(entries.lastOrNull()?.id == entry.id)
            write(entries)
            true
        } catch (_: Exception) {
            lastError = "Could not save this test-history entry. Your draft is still available."
            false
        }
    }

    /** Old entries expire on the next read or record. Unreadable history is never silently replaced. */
    fun read(): List<HistoryEntry> = synchronized(LOCK) {
        lastError = null
        try {
            val loaded = load()
            val retained = HistoryJournal.retain(loaded, System.currentTimeMillis())
            if (retained.size != loaded.size) write(retained)
            retained
        } catch (_: Exception) {
            lastError = "Could not read local test history. Clear it to start a new log."
            emptyList()
        }
    }

    fun clear(): Boolean = synchronized(LOCK) {
        lastError = null
        try {
            check(preferences.edit().putLong("generation", Math.addExact(generation, 1L)).commit())
            file.delete()
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists())
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keys.containsAlias(ALIAS)) keys.deleteEntry(ALIAS)
            true
        } catch (_: Exception) {
            lastError = "Could not clear local test history. Please try again."
            false
        }
    }

    /** Read only after the user chooses an export destination. No cached or deleted snapshot is used. */
    fun exportJson(): String? = synchronized(LOCK) {
        val entries = read()
        if (lastError != null) null else HistoryJournal.encode(entries)
    }

    private fun load(): List<HistoryEntry> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var count = input.read(buffer)
            while (count != -1) {
                check(output.size() + count <= HistoryJournal.MAX_BYTES + ENVELOPE_BYTES)
                output.write(buffer, 0, count)
                count = input.read(buffer)
            }
            output.toByteArray()
        }
        check(bytes.size > ENVELOPE_BYTES && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        cipher.updateAAD(MAGIC)
        val plaintext = cipher.doFinal(bytes, 16, bytes.size - 16)
        return HistoryJournal.decode(String(plaintext, Charsets.UTF_8))
    }

    private fun write(entries: List<HistoryEntry>) {
        val plaintext = HistoryJournal.encode(entries).toByteArray(Charsets.UTF_8)
        check(plaintext.size <= HistoryJournal.MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        check(cipher.iv.size == 12)
        cipher.updateAAD(MAGIC)
        val bytes = MAGIC + cipher.iv + cipher.doFinal(plaintext)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    companion object {
        private const val ALIAS = "rawplus.local.test-history"
        private const val ENVELOPE_BYTES = 32 // Four-byte marker, 12-byte IV and 16-byte GCM tag.
        private val MAGIC = byteArrayOf(0x52, 0x50, 0x48, 0x31)
        private val LOCK = Any()
    }
}
