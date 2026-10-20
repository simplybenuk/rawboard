package uk.co.simplyben.rawplus

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64

data class ChatGptAccount(
    val id: String,
    val label: String,
    val connected: Boolean,
    val planEnabled: Boolean,
    val selectedModel: String?
)

data class ChatGptModel(val slug: String, val displayName: String)

/** A credential is deliberately excluded from generated data-class diagnostics. */
class ChatGptAccess internal constructor(
    val accountId: String,
    val model: String,
    val token: String,
    internal val sessionEpoch: String,
    internal val selectionEpoch: String
) {
    override fun toString() = "ChatGptAccess(accountId=$accountId, model=$model, token=<redacted>)"
}

internal class ChatGptAuthFailure(message: String) : Exception(message)

internal class ChatGptPending(
    val state: String,
    val nonce: String,
    val verifier: String,
    val redirectUri: String,
    val clientId: String,
    val hostId: String,
    val accountId: String? = null,
    val subject: String? = null,
    val email: String? = null,
    val idTokenHint: String? = null,
    val expectedEpoch: String? = null
) {
    override fun toString() = "ChatGptPending(<redacted>)"
}

internal class ChatGptCallback(val code: String?, val clientId: String, val denied: Boolean) {
    override fun toString() = "ChatGptCallback(<redacted>)"
}

internal class ChatGptTokens(
    val access: String,
    val refresh: String?,
    val idToken: String?,
    val scopes: Set<String>,
    val expiresAt: Long,
    val earliestRefreshAt: Long
) {
    val planEnabled get() = ChatGptAuthProtocol.planEnabled(scopes)
    override fun toString() = "ChatGptTokens(<redacted>)"
}

internal data class ChatGptIdentity(val subject: String, val email: String?, val emailVerified: Boolean)

/** Pure, bounded OAuth/OIDC parsing. None of the errors include server-supplied text. */
internal object ChatGptAuthProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val MODELS = "$RESOURCE/models"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val APP_NAME = "Raw+ Keyboard"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val MAX_TOKEN = 32_768
    const val MAX_RESPONSE = 512 * 1024
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val opaque = Regex("[A-Za-z0-9._~+/-]+=*")
    private val client = Regex("[A-Za-z0-9_-]{3,256}")
    private val model = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")

    fun randomValue(random: SecureRandom = SecureRandom()): String =
        encoder.encodeToString(ByteArray(32).also(random::nextBytes))

    fun challenge(verifier: String): String {
        require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
        return encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
    }

    fun constantEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8), actual.toByteArray(StandardCharsets.UTF_8))

    fun issuedClient(value: String): Boolean = value != DYNAMIC_CLIENT && client.matches(value)

    fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    fun needsPlanConsent(returningRegistration: Boolean, tokens: ChatGptTokens?): Boolean =
        returningRegistration && tokens?.planEnabled != true

    fun authorize(pending: ChatGptPending, forceConsent: Boolean = false): String {
        check(pending.hostId.startsWith("urn:uuid:") && pending.hostId.length <= 64)
        require(pending.clientId == DYNAMIC_CLIENT || issuedClient(pending.clientId))
        val values = linkedMapOf(
            "client_id" to pending.clientId,
            "ext_agent_host_id" to pending.hostId,
            "response_type" to "code",
            "redirect_uri" to pending.redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to pending.state,
            "nonce" to pending.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge(pending.verifier)
        )
        if (pending.clientId == DYNAMIC_CLIENT) values["agent_name_hint"] = APP_NAME
        else {
            pending.idTokenHint?.let { values["id_token_hint"] = it }
            pending.email?.let { values["login_hint"] = it }
            // Only an explicit attempt to enable missing plan permission repeats consent.
            // force_reconsent is not deployed for this integration; the documented OAuth
            // prompt=consent mechanism is supported before that rollout.
            if (forceConsent) values["prompt"] = "consent"
        }
        return "$AUTHORIZE?${form(values)}"
    }

    /** Called only after the listener validates method, headers and exact loopback Host. */
    fun callback(target: String, pending: ChatGptPending): ChatGptCallback {
        require(target.length <= 16_384 && !target.any { it.code < 32 || it.code == 127 })
        val uri = URI(target)
        require(uri.scheme == null && uri.rawAuthority == null && uri.fragment == null)
        require(uri.rawPath == "/auth/callback")
        val query = uri.rawQuery ?: throw IllegalArgumentException()
        val values = linkedMapOf<String, String>()
        val pieces = query.split('&')
        require(pieces.size in 1..20)
        pieces.forEach { piece ->
            val pair = piece.split('=', limit = 2)
            require(pair.size == 2)
            val key = decode(pair[0])
            val value = decode(pair[1])
            require(key.length in 1..100 && value.length <= 8192 && !values.containsKey(key))
            require(!value.any { it.code < 32 || it.code == 127 })
            values[key] = value
        }
        val state = values["state"] ?: throw IllegalArgumentException()
        require(constantEquals(pending.state, state))
        values["iss"]?.let { require(it == ISSUER) }
        // A matching-state error consumes this attempt, but never exposes its description.
        if (values.containsKey("error")) {
            require(!values.containsKey("code"))
            return ChatGptCallback(null, pending.clientId, true)
        }
        val returned = values["client_id"]
        val issued = if (pending.clientId == DYNAMIC_CLIENT) {
            require(returned != null && issuedClient(returned))
            returned
        } else {
            require(returned == null || returned == pending.clientId)
            pending.clientId
        }
        val code = values["code"] ?: throw IllegalArgumentException()
        require(code.isNotEmpty() && code.length <= 8192)
        return ChatGptCallback(code, issued, false)
    }

    fun codeGrant(callback: ChatGptCallback, pending: ChatGptPending): String {
        require(!callback.denied && issuedClient(callback.clientId) && !callback.code.isNullOrEmpty())
        return form(linkedMapOf("grant_type" to "authorization_code", "client_id" to callback.clientId,
            "code" to checkNotNull(callback.code), "code_verifier" to pending.verifier,
            "redirect_uri" to pending.redirectUri, "resource" to RESOURCE))
    }

    fun refreshGrant(clientId: String, refresh: String): String {
        require(issuedClient(clientId)); validateToken(refresh)
        return form(linkedMapOf("grant_type" to "refresh_token", "client_id" to clientId,
            "refresh_token" to refresh, "resource" to RESOURCE))
    }

    fun planEnabled(scopes: Set<String>) = "resource.invoke" in scopes && "chatgpt.tokens.use.direct" in scopes

    fun tokens(json: String, now: Long, previous: ChatGptTokens? = null): ChatGptTokens {
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE && now > 0)
        val value = JSONObject(json)
        val access = string(value, "access_token").also(::validateToken)
        require(string(value, "token_type").equals("Bearer", ignoreCase = true))
        val expires = integer(value, "expires_in")
        require(expires in 1..86_400) // Documented lifetime is one hour. Reject nonsensical server data.
        val scope = if (value.has("scope")) string(value, "scope") else {
            require(previous != null)
            previous.scopes.joinToString(" ")
        }
        require(scope.length <= 4096)
        val scopes = scope.split(' ').filter { it.isNotEmpty() }.toSet()
        require(scopes.size <= 64 && scopes.all { it.matches(Regex("[A-Za-z0-9_.:/-]{1,128}")) })
        val refresh = optionalString(value, "refresh_token")?.also(::validateToken)
        // A renewable grant must return its replacement; never reuse an already-rotated token.
        require(refresh != null || ("offline_access" !in scopes && previous?.refresh == null))
        val idToken = optionalString(value, "id_token")?.also { require(it.length in 1..MAX_TOKEN) }
        require(idToken != null || previous != null)
        val earliest = if (!value.has("earliest_refresh_at") || value.isNull("earliest_refresh_at")) 0L else {
            when (val raw = value.get("earliest_refresh_at")) {
                is Int, is Long -> Math.multiplyExact((raw as Number).toLong(), 1000L)
                is String -> if (raw.matches(Regex("[0-9]{1,12}"))) Math.multiplyExact(raw.toLong(), 1000L)
                    else Instant.parse(raw).toEpochMilli()
                else -> throw IllegalArgumentException()
            }.also { require(it >= 0 && it <= now + expires * 1000L) }
        }
        return ChatGptTokens(access, refresh, idToken ?: previous?.idToken, scopes,
            Math.addExact(now, expires * 1000L), earliest)
    }

    fun models(json: String): List<ChatGptModel> {
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE)
        val array = JSONObject(json).getJSONArray("models")
        require(array.length() <= 1000)
        val found = mutableSetOf<String>()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.getJSONObject(i)
                if (value.opt("visibility") != "list") continue
                val slug = string(value, "slug")
                val displayName = string(value, "display_name")
                require(validModel(slug) && displayName.length in 1..256 && displayName.none { it.isISOControl() })
                require(found.add(slug))
                add(ChatGptModel(slug, displayName))
            }
        }
    }

    fun validModel(value: String) = model.matches(value)

    /** Only the discovered public endpoints on the fixed OpenAI auth origin are accepted. */
    fun discovery(json: String): Pair<String, String> {
        require(json.length <= 65_536)
        val value = JSONObject(json)
        require(string(value, "issuer") == ISSUER)
        require(string(value, "authorization_endpoint") == AUTHORIZE && string(value, "token_endpoint") == TOKEN)
        val jwks = string(value, "jwks_uri").also(::authEndpoint)
        val revoke = string(value, "revocation_endpoint").also(::authEndpoint)
        require(value.getJSONArray("id_token_signing_alg_values_supported").let { list ->
            (0 until list.length()).any { list.get(it) == "RS256" }
        })
        return jwks to revoke
    }

    fun authEndpoint(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "auth.openai.com" && (uri.port == -1 || uri.port == 443))
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && !uri.rawPath.isNullOrEmpty())
    }

    fun validateIdToken(jwt: String, jwksJson: String, clientId: String, nonce: String?, now: Long): ChatGptIdentity {
        require(jwt.length in 1..MAX_TOKEN && issuedClient(clientId) && jwksJson.length <= 262_144)
        val parts = jwt.split('.')
        require(parts.size == 3 && parts.all { it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9_-]+")) })
        val header = JSONObject(String(decoder.decode(parts[0]), StandardCharsets.UTF_8))
        require(string(header, "alg") == "RS256")
        require(!header.has("crit") && !header.has("b64"))
        val kid = string(header, "kid")
        require(kid.length in 1..256)
        val keys = JSONObject(jwksJson).getJSONArray("keys")
        require(keys.length() in 1..32)
        val matching = (0 until keys.length()).map(keys::getJSONObject).filter { it.opt("kid") == kid }
        require(matching.size == 1)
        val key = matching.single()
        require(string(key, "kty") == "RSA" && (!key.has("alg") || key.get("alg") == "RS256"))
        require(!key.has("use") || key.get("use") == "sig")
        if (key.has("key_ops")) {
            val operations = key.getJSONArray("key_ops")
            require((0 until operations.length()).any { operations.get(it) == "verify" })
        }
        val modulus = BigInteger(1, decoder.decode(string(key, "n")))
        val exponent = BigInteger(1, decoder.decode(string(key, "e")))
        require(modulus.bitLength() in 2048..8192 && exponent.bitLength() <= 32 && exponent > BigInteger.ONE && exponent.testBit(0))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        require(verifier.verify(decoder.decode(parts[2])))
        val claims = JSONObject(String(decoder.decode(parts[1]), StandardCharsets.UTF_8))
        require(string(claims, "iss") == ISSUER)
        when (val audience = claims.get("aud")) {
            is String -> require(audience == clientId)
            is JSONArray -> {
                require(audience.length() in 1..8 && (0 until audience.length()).all { audience.get(it) is String })
                require((0 until audience.length()).any { audience.get(it) == clientId })
                if (audience.length() > 1) require(claims.opt("azp") == clientId)
            }
            else -> throw IllegalArgumentException()
        }
        if (claims.has("azp")) require(claims.get("azp") == clientId)
        val seconds = now / 1000L
        val expiry = integer(claims, "exp")
        val issuedAt = integer(claims, "iat")
        require(expiry > seconds - 60 && issuedAt <= seconds + 60 && issuedAt > 0 && expiry > issuedAt)
        if (claims.has("nbf")) require(integer(claims, "nbf") <= seconds + 60)
        if (nonce != null) require(constantEquals(nonce, string(claims, "nonce")))
        val subject = string(claims, "sub")
        require(subject.length in 1..512 && subject.none { it.isISOControl() })
        val email = optionalString(claims, "email")?.also {
            require(it.length in 1..320 && it.none { char -> char.isISOControl() })
        }
        val verified = claims.opt("email_verified") == true
        return ChatGptIdentity(subject, email, verified)
    }

    fun validateToken(value: String) {
        require(value.length in 1..MAX_TOKEN && opaque.matches(value))
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    internal fun string(value: JSONObject, name: String) = value.get(name) as? String ?: throw IllegalArgumentException()
    internal fun optionalString(value: JSONObject, name: String): String? =
        if (!value.has(name) || value.isNull(name)) null else string(value, name)
    internal fun integer(value: JSONObject, name: String): Long = when (val number = value.get(name)) {
        is Long -> number
        is Int -> number.toLong()
        else -> throw IllegalArgumentException()
    }
}
