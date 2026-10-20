package uk.co.simplyben.rawplus

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGptAuthProtocolTest {
    private val now = 1_700_000_000_000L
    private fun pending(client: String = ChatGptAuthProtocol.DYNAMIC_CLIENT) = ChatGptPending(
        "fresh-state", "fresh-nonce", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        "http://127.0.0.1:45678/auth/callback", client,
        "urn:uuid:123e4567-e89b-12d3-a456-426614174000")

    @Test fun `PKCE matches the RFC 7636 known answer and random values have 256 bits`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptAuthProtocol.challenge(pending().verifier))
        val values = (1..30).map { ChatGptAuthProtocol.randomValue() }
        assertEquals(30, values.toSet().size)
        assertTrue(values.all { it.length == 43 && it.matches(Regex("[A-Za-z0-9_-]+")) })
    }

    @Test fun `dynamic authorization has all plan scopes and exact callback without a secret`() {
        val uri = URI(ChatGptAuthProtocol.authorize(pending()))
        val query = uri.rawQuery.split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("https://auth.openai.com/api/accounts/authorize", "${uri.scheme}://${uri.host}${uri.path}")
        assertEquals("Raw+ Keyboard", query["agent_name_hint"])
        assertEquals(pending().redirectUri, query["redirect_uri"])
        assertEquals(ChatGptAuthProtocol.SCOPES, query["scope"])
        assertEquals("S256", query["code_challenge_method"])
        assertFalse(query.containsKey("client_secret"))
        assertFalse(query.containsKey("id_token_hint"))
    }

    @Test fun `returning authorization reuses registration and omits dynamic name metadata`() {
        val url = ChatGptAuthProtocol.authorize(pending("oaiapp_saved"))
        assertTrue(url.contains("client_id=oaiapp_saved"))
        assertFalse(url.contains("agent_name_hint"))
        assertFalse(url.contains("prompt="))
    }

    @Test fun `explicit reauthorization requests consent only for returning accounts missing plan permission`() {
        val granted = ChatGptAuthProtocol.tokens(tokenJson().toString(), now)
        val identityOnly = ChatGptAuthProtocol.tokens(tokenJson().put("scope", "openid profile email")
            .removeAlso("refresh_token").toString(), now)
        fun authorization(returning: Boolean, tokens: ChatGptTokens?) = ChatGptAuthProtocol.authorize(
            pending(if (returning) "oaiapp_saved" else ChatGptAuthProtocol.DYNAMIC_CLIENT),
            ChatGptAuthProtocol.needsPlanConsent(returning, tokens))
        assertFalse(authorization(false, null).contains("prompt="))
        assertFalse(authorization(true, granted).contains("prompt="))
        assertTrue(authorization(true, identityOnly).contains("prompt=consent"))
        assertTrue(authorization(true, null).contains("prompt=consent"))
        assertFalse(authorization(true, identityOnly).contains("force_reconsent"))
        assertTrue(authorization(true, identityOnly).contains("chatgpt.tokens.use.direct"))
        // Dynamic registration already presents its approval step.
        assertFalse(ChatGptAuthProtocol.authorize(pending(), forceConsent = true).contains("prompt="))
    }

    @Test fun `callback requires bound state exact path and an issued registration`() {
        val accepted = ChatGptAuthProtocol.callback("/auth/callback?state=fresh-state&code=code&client_id=oaiapp_issued", pending())
        assertEquals("oaiapp_issued", accepted.clientId)
        assertFalse(accepted.denied)
        listOf(
            "/auth/callback?state=other&code=code&client_id=oaiapp_issued",
            "/auth/callback?state=fresh-state&code=code",
            "/auth/callback?state=fresh-state&code=code&client_id=dynamic_agent_client",
            "/callback?state=fresh-state&code=code&client_id=oaiapp_issued",
            "http://evil.example/auth/callback?state=fresh-state&code=code&client_id=oaiapp_issued",
            "/auth/callback?state=fresh-state&code=code&client_id=oaiapp_issued#fragment",
            "/auth/callback?state=fresh-state&code=code&client_id=oaiapp_issued&iss=https%3A%2F%2Fevil.example"
        ).forEach { target -> assertThrows(Exception::class.java) { ChatGptAuthProtocol.callback(target, pending()) } }
    }

    @Test fun `duplicate callback parameters cannot change the state code or client selected by parser`() {
        listOf("state=fresh-state&state=bad&code=code&client_id=oaiapp_issued",
            "state=fresh-state&code=one&code=two&client_id=oaiapp_issued",
            "state=fresh-state&code=code&client_id=oaiapp_one&client_id=oaiapp_two",
            "state=fresh-state&%73tate=fresh-state&code=code&client_id=oaiapp_issued")
            .forEach { query -> assertThrows(Exception::class.java) { ChatGptAuthProtocol.callback("/auth/callback?$query", pending()) } }
    }

    @Test fun `returning callback may omit client but cannot change the selected registration`() {
        val p = pending("oaiapp_saved")
        assertEquals("oaiapp_saved", ChatGptAuthProtocol.callback("/auth/callback?state=fresh-state&code=code", p).clientId)
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.callback("/auth/callback?state=fresh-state&code=code&client_id=oaiapp_other", p) }
        assertTrue(ChatGptAuthProtocol.callback("/auth/callback?state=fresh-state&error=access_denied", p).denied)
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.callback("/auth/callback?state=bad&error=access_denied", p) }
    }

    @Test fun `code exchange uses issued client PKCE and same callback and refresh does not expand scope`() {
        val callback = ChatGptAuthProtocol.callback("/auth/callback?state=fresh-state&code=a%2Bb&client_id=oaiapp_issued", pending())
        val form = ChatGptAuthProtocol.codeGrant(callback, pending())
        assertTrue(form.contains("client_id=oaiapp_issued"))
        assertTrue(form.contains("code=a%2Bb"))
        assertTrue(form.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2Fauth%2Fcallback"))
        assertFalse(form.contains("dynamic_agent_client"))
        val refresh = ChatGptAuthProtocol.refreshGrant("oaiapp_issued", "refresh-secret")
        assertFalse(refresh.contains("scope="))
        assertFalse(refresh.contains("client_secret"))
    }

    @Test fun `only token response grants enable plan usage and identity-only scope remains disabled`() {
        val plan = ChatGptAuthProtocol.tokens(tokenJson().toString(), now)
        assertTrue(plan.planEnabled)
        val identity = tokenJson().put("scope", "openid profile email").removeAlso("refresh_token")
        assertFalse(ChatGptAuthProtocol.tokens(identity.toString(), now).planEnabled)
        assertFalse(ChatGptAuthProtocol.tokens(tokenJson().put("scope", "resource.invoke openid").toString(), now).planEnabled)
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.tokens(tokenJson().removeAlso("scope").toString(), now) }
    }

    @Test fun `refresh replaces renewable tokens and omitted scope retains only previous grant`() {
        val original = ChatGptAuthProtocol.tokens(tokenJson().toString(), now)
        val refreshed = ChatGptAuthProtocol.tokens(tokenJson().put("access_token", "new-access").put("refresh_token", "new-refresh")
            .removeAlso("scope").removeAlso("id_token").toString(), now + 3_000_000L, original)
        assertEquals("new-refresh", refreshed.refresh)
        assertEquals(original.idToken, refreshed.idToken)
        assertEquals(original.scopes, refreshed.scopes)
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.tokens(tokenJson().removeAlso("refresh_token").toString(), now, original) }
    }

    @Test fun `token schema and bounds reject header injection malformed expiry and unbounded refresh times`() {
        listOf(tokenJson().put("access_token", "token\r\nInjected: secret"), tokenJson().put("token_type", "Basic"),
            tokenJson().put("expires_in", 0), tokenJson().put("expires_in", 3600.5),
            tokenJson().put("earliest_refresh_at", Long.MAX_VALUE), tokenJson().put("access_token", "x".repeat(32_769)))
            .forEach { value -> assertThrows(Exception::class.java) { ChatGptAuthProtocol.tokens(value.toString(), now) } }
        assertEquals(now + 1800_000L, ChatGptAuthProtocol.tokens(tokenJson().put("earliest_refresh_at", now / 1000L + 1800).toString(), now).earliestRefreshAt)
    }

    @Test fun `model picker keeps only server list visibility preserves order and rejects duplicates`() {
        val catalog = JSONObject().put("models", JSONArray().put(model("two", "Second"))
            .put(model("hidden", "Hidden").put("visibility", "hidden")).put(model("one", "First")))
        assertEquals(listOf(ChatGptModel("two", "Second"), ChatGptModel("one", "First")), ChatGptAuthProtocol.models(catalog.toString()))
        catalog.getJSONArray("models").put(model("one", "Duplicate"))
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.models(catalog.toString()) }
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.models("{\"data\":[{\"id\":\"api-key-model\"}]}") }
    }

    @Test fun `OIDC discovery cannot redirect keys or revocation credentials to another origin`() {
        val valid = discovery()
        assertEquals("https://auth.openai.com/.well-known/jwks.json", ChatGptAuthProtocol.discovery(valid.toString()).first)
        listOf("http://auth.openai.com/keys", "https://auth.openai.com.evil.example/keys", "https://evil.example/keys",
            "https://auth.openai.com@evil.example/keys", "https://auth.openai.com/keys?redirect=evil")
            .forEach { url -> assertThrows(Exception::class.java) { ChatGptAuthProtocol.discovery(discovery().put("jwks_uri", url).toString()) } }
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.discovery(discovery().put("issuer", "https://evil.example").toString()) }
    }

    @Test fun `ID identity is returned only after RSA signature issuer audience expiry and nonce validation`() {
        val identity = ChatGptAuthProtocol.validateIdToken(jwt(claims()), jwks(), "oaiapp_issued", "fresh-nonce", now)
        assertEquals(ChatGptIdentity("verified-subject", "person@example.com", true), identity)
        val invalid = listOf(claims().put("iss", "https://evil.example"), claims().put("aud", "other-client"),
            claims().put("nonce", "wrong-nonce"), claims().put("exp", now / 1000 - 61),
            claims().put("iat", now / 1000 + 61), claims().put("sub", ""))
        invalid.forEach { value -> assertThrows(Exception::class.java) { ChatGptAuthProtocol.validateIdToken(jwt(value), jwks(), "oaiapp_issued", "fresh-nonce", now) } }
    }

    @Test fun `forged signature none algorithm unknown kid and ambiguous audience are rejected`() {
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.validateIdToken(jwt(claims(), alternate), jwks(), "oaiapp_issued", "fresh-nonce", now) }
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.validateIdToken(jwt(claims(), header = JSONObject().put("alg", "none").put("kid", "key-1")), jwks(), "oaiapp_issued", "fresh-nonce", now) }
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.validateIdToken(jwt(claims(), header = JSONObject().put("alg", "RS256").put("kid", "unknown")), jwks(), "oaiapp_issued", "fresh-nonce", now) }
        val multi = claims().put("aud", JSONArray().put("oaiapp_issued").put("second-client"))
        assertThrows(Exception::class.java) { ChatGptAuthProtocol.validateIdToken(jwt(multi), jwks(), "oaiapp_issued", "fresh-nonce", now) }
        multi.put("azp", "oaiapp_issued")
        assertEquals("verified-subject", ChatGptAuthProtocol.validateIdToken(jwt(multi), jwks(), "oaiapp_issued", "fresh-nonce", now).subject)
    }

    @Test fun `secret-bearing objects redact their diagnostic representation`() {
        val tokens = ChatGptAuthProtocol.tokens(tokenJson().toString(), now)
        assertFalse(tokens.toString().contains("access-secret"))
        assertFalse(ChatGptAccess("account", "model", "access-secret", "epoch", "selection").toString().contains("access-secret"))
        assertFalse(pending().toString().contains(pending().verifier))
        assertFalse(ChatGptCallback("auth-secret", "client", false).toString().contains("auth-secret"))
    }

    private fun tokenJson() = JSONObject().put("access_token", "access-secret").put("refresh_token", "refresh-secret")
        .put("id_token", "opaque-ID-token").put("token_type", "Bearer").put("expires_in", 3600)
        .put("scope", ChatGptAuthProtocol.SCOPES)
    private fun model(slug: String, name: String) = JSONObject().put("slug", slug).put("display_name", name).put("visibility", "list")
    private fun discovery() = JSONObject().put("issuer", ChatGptAuthProtocol.ISSUER)
        .put("authorization_endpoint", ChatGptAuthProtocol.AUTHORIZE).put("token_endpoint", ChatGptAuthProtocol.TOKEN)
        .put("jwks_uri", "https://auth.openai.com/.well-known/jwks.json")
        .put("revocation_endpoint", "https://auth.openai.com/api/accounts/oauth/revoke")
        .put("id_token_signing_alg_values_supported", JSONArray().put("RS256"))
    private fun claims() = JSONObject().put("iss", ChatGptAuthProtocol.ISSUER).put("aud", "oaiapp_issued")
        .put("sub", "verified-subject").put("email", "person@example.com").put("email_verified", true)
        .put("nonce", "fresh-nonce").put("iat", now / 1000 - 30).put("exp", now / 1000 + 3600)
    private fun jwt(claims: JSONObject, pair: KeyPair = signing, header: JSONObject = JSONObject().put("alg", "RS256").put("kid", "key-1")): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val data = "${encoder.encodeToString(header.toString().toByteArray(StandardCharsets.UTF_8))}.${encoder.encodeToString(claims.toString().toByteArray(StandardCharsets.UTF_8))}"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private); update(data.toByteArray(StandardCharsets.US_ASCII)); sign()
        }
        return "$data.${encoder.encodeToString(signature)}"
    }
    private fun jwks(): String {
        val public = signing.public as RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "key-1").put("kty", "RSA")
            .put("use", "sig").put("alg", "RS256").put("n", encoder.encodeToString(public.modulus.toByteArray()))
            .put("e", encoder.encodeToString(public.publicExponent.toByteArray())))).toString()
    }
    private fun JSONObject.removeAlso(key: String) = apply { remove(key) }

    companion object {
        private val signing = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val alternate = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
