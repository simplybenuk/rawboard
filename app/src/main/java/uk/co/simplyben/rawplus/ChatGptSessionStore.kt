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

internal data class ChatGptRecord(
    val id: String,
    val clientId: String,
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val tokens: ChatGptTokens?,
    val epoch: String,
    val models: List<ChatGptModel> = emptyList(),
    val selectedModel: String? = null
) {
    fun publicAccount() = ChatGptAccount(id,
        (if (emailVerified) email else null)?.let { "$it · ${id.take(6)}" } ?: "ChatGPT account ${id.take(6)}",
        tokens != null, tokens?.planEnabled == true, selectedModel)
    override fun toString() = "ChatGptRecord(id=$id, credentials=<redacted>)"
}

internal data class ChatGptSessions(
    val hostId: String,
    val selected: String?,
    val selectionEpoch: String,
    val records: List<ChatGptRecord>
) {
    fun selectedRecord() = records.firstOrNull { it.id == selected }
    override fun toString() = "ChatGptSessions(<redacted>)"
}

/** Codec kept separate so credential pairing and disconnection can be verified on the JVM. */
internal object ChatGptSessionCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ACCOUNTS = 10

    fun encode(sessions: ChatGptSessions): String = JSONObject().apply {
        put("version", 1)
        put("host_id", sessions.hostId)
        put("selected", sessions.selected ?: JSONObject.NULL)
        put("selection_epoch", sessions.selectionEpoch)
        put("accounts", JSONArray().apply {
            sessions.records.forEach { record -> put(JSONObject().apply {
                put("id", record.id); put("client_id", record.clientId); put("subject", record.subject)
                put("email", record.email ?: JSONObject.NULL); put("email_verified", record.emailVerified)
                put("epoch", record.epoch); put("selected_model", record.selectedModel ?: JSONObject.NULL)
                put("models", JSONArray().apply { record.models.forEach { model ->
                    put(JSONObject().put("slug", model.slug).put("display_name", model.displayName))
                } })
                put("tokens", record.tokens?.let { tokens -> JSONObject().apply {
                    put("access", tokens.access); put("refresh", tokens.refresh ?: JSONObject.NULL)
                    put("id_token", tokens.idToken ?: JSONObject.NULL); put("expires_at", tokens.expiresAt)
                    put("earliest_refresh_at", tokens.earliestRefreshAt)
                    put("scopes", JSONArray(tokens.scopes.toList()))
                } } ?: JSONObject.NULL)
            }) }
        })
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    fun decode(json: String): ChatGptSessions {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val value = JSONObject(json)
        require(value.get("version") == 1)
        val host = ChatGptAuthProtocol.string(value, "host_id")
        require(host.startsWith("urn:uuid:") && UUID.fromString(host.removePrefix("urn:uuid:")).toString() == host.removePrefix("urn:uuid:"))
        val selected = ChatGptAuthProtocol.optionalString(value, "selected")
        val selectionEpoch = uuid(ChatGptAuthProtocol.string(value, "selection_epoch"))
        val array = value.getJSONArray("accounts")
        require(array.length() <= MAX_ACCOUNTS)
        val ids = mutableSetOf<String>()
        val registrations = mutableSetOf<Pair<String, String>>()
        val records = List(array.length()) { index ->
            val account = array.getJSONObject(index)
            fun str(name: String) = ChatGptAuthProtocol.string(account, name)
            val id = uuid(str("id")); require(ids.add(id))
            val clientId = str("client_id"); require(ChatGptAuthProtocol.issuedClient(clientId))
            val subject = str("subject"); require(subject.length in 1..512 && subject.none { it.isISOControl() })
            require(registrations.add(clientId to subject))
            val email = ChatGptAuthProtocol.optionalString(account, "email")
            require(email == null || (email.length in 1..320 && email.none { it.isISOControl() }))
            val verified = account.get("email_verified") as? Boolean ?: throw IllegalArgumentException()
            val epoch = uuid(str("epoch"))
            val models = account.getJSONArray("models")
            require(models.length() <= 1000)
            val slugs = mutableSetOf<String>()
            val catalog = List(models.length()) { modelIndex ->
                val model = models.getJSONObject(modelIndex)
                val slug = ChatGptAuthProtocol.string(model, "slug")
                val name = ChatGptAuthProtocol.string(model, "display_name")
                require(ChatGptAuthProtocol.validModel(slug) && slugs.add(slug))
                require(name.length in 1..256 && name.none { it.isISOControl() })
                ChatGptModel(slug, name)
            }
            val selectedModel = ChatGptAuthProtocol.optionalString(account, "selected_model")
            require(selectedModel == null || catalog.any { it.slug == selectedModel })
            val tokens = if (account.isNull("tokens")) null else {
                val raw = account.getJSONObject("tokens")
                val access = ChatGptAuthProtocol.string(raw, "access").also(ChatGptAuthProtocol::validateToken)
                val refresh = ChatGptAuthProtocol.optionalString(raw, "refresh")?.also(ChatGptAuthProtocol::validateToken)
                val idToken = ChatGptAuthProtocol.optionalString(raw, "id_token")?.also { require(it.length in 1..ChatGptAuthProtocol.MAX_TOKEN) }
                val scopesJson = raw.getJSONArray("scopes"); require(scopesJson.length() <= 64)
                val scopes = List(scopesJson.length()) { scopesJson.get(it) as? String ?: throw IllegalArgumentException() }.toSet()
                require(scopes.all { it.matches(Regex("[A-Za-z0-9_.:/-]{1,128}")) })
                val expires = ChatGptAuthProtocol.integer(raw, "expires_at")
                val earliest = ChatGptAuthProtocol.integer(raw, "earliest_refresh_at")
                require(expires > 0 && earliest in 0..expires)
                ChatGptTokens(access, refresh, idToken, scopes, expires, earliest)
            }
            require(tokens != null || (catalog.isEmpty() && selectedModel == null))
            ChatGptRecord(id, clientId, subject, email, verified, tokens, epoch, catalog, selectedModel)
        }
        require(selected == null || records.any { it.id == selected })
        return ChatGptSessions(host, selected, selectionEpoch, records)
    }

    private fun uuid(value: String): String = UUID.fromString(value).toString().also { require(it == value) }
}

/** One process-wide lock/cache covers settings and the IME. Network calls never hold it. */
internal class ChatGptSessionStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "chatgpt-sessions.enc"))
    private val path = file.baseFile.absolutePath

    fun read(): ChatGptSessions = synchronized(LOCK) { load() }

    fun update(change: (ChatGptSessions) -> ChatGptSessions): ChatGptSessions = synchronized(LOCK) {
        val before = load()
        val after = change(before)
        if (after !== before) write(after)
        after
    }

    /** Persist the opaque host before a browser is launched, including on the first installation. */
    fun ensureHost(): String = synchronized(LOCK) {
        val existing = load()
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) write(existing)
        existing.hostId
    }

    private fun load(): ChatGptSessions {
        CACHE[path]?.let { return it }
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return ChatGptSessions("urn:uuid:${UUID.randomUUID()}", null, UUID.randomUUID().toString(), emptyList())
                .also { CACHE[path] = it }
        }
        val bytes = file.openRead().use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(result.size() + count <= ChatGptSessionCodec.MAX_BYTES + 32)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        require(bytes.size > 32 && bytes.copyOfRange(0, 4).contentEquals(MAGIC))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        cipher.updateAAD(MAGIC)
        val plain = cipher.doFinal(bytes, 16, bytes.size - 16)
        return ChatGptSessionCodec.decode(String(plain, Charsets.UTF_8)).also { CACHE[path] = it }
    }

    private fun write(sessions: ChatGptSessions) {
        val plain = ChatGptSessionCodec.encode(sessions).toByteArray(Charsets.UTF_8)
        // Round-trip validation prevents persisting mismatched registrations or credentials.
        ChatGptSessionCodec.decode(String(plain, Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == 12)
        cipher.updateAAD(MAGIC)
        val encrypted = MAGIC + cipher.iv + cipher.doFinal(plain)
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
            CACHE[path] = sessions
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
        private const val ALIAS = "rawplus.chatgpt.sessions"
        private val MAGIC = byteArrayOf(0x52, 0x43, 0x47, 0x31)
        private val LOCK = Any()
        private val CACHE = mutableMapOf<String, ChatGptSessions>()
    }
}
