package uk.co.simplyben.rawplus

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Official public-client OAuth only. No imported credentials or ChatGPT private endpoints. */
class ChatGptAuth(context: Context) {
    private val app = context.applicationContext
    private val store = ChatGptSessionStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val connections = mutableSetOf<HttpURLConnection>()
    private val connectionLock = Any()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var job: Future<*>? = null
    @Volatile private var closed = false
    // Kept only for retrying a code exchange interrupted after a validated registration callback.
    @Volatile private var pendingRegistrationClient: String? = null

    fun accounts(): List<ChatGptAccount> = try { store.read().records.map { it.publicAccount() } }
        catch (_: Exception) { emptyList() }

    fun selectedAccount(): ChatGptAccount? = try { store.read().selectedRecord()?.publicAccount() }
        catch (_: Exception) { null }

    fun selectAccount(id: String) = safeStore {
        store.update { sessions ->
            require(sessions.records.any { it.id == id })
            if (sessions.selected == id) sessions else sessions.copy(selected = id, selectionEpoch = epoch())
        }
        Unit
    }

    fun selectModel(slug: String) = safeStore {
        store.update { sessions ->
            val record = sessions.selectedRecord() ?: throw ChatGptAuthFailure("Connect a ChatGPT account first.")
            require(record.tokens?.planEnabled == true && record.models.any { it.slug == slug })
            if (record.selectedModel == slug) sessions else sessions.copy(selectionEpoch = epoch(),
                records = sessions.records.map { if (it.id == record.id) it.copy(selectedModel = slug) else it })
        }
        Unit
    }

    /** Explicit metadata request. It does not run model inference or choose a default model. */
    fun listModels(callback: (Result<List<ChatGptModel>>) -> Unit) {
        async(callback) { operation ->
            val snapshot = store.read()
            val record = connected(snapshot)
            val refreshed = credentials(record.id, record.epoch, snapshot.selectionEpoch, operation) { false }
            val models = ChatGptAuthProtocol.models(http(ChatGptAuthProtocol.MODELS, "GET", operation, bearer = refreshed.access))
            checkActive(operation)
            store.update { current ->
                val latest = current.selectedRecord()
                if (current.selectionEpoch != snapshot.selectionEpoch || latest?.id != record.id ||
                    latest.epoch != record.epoch || latest.tokens == null) throw CancellationException()
                val chosen = latest.selectedModel?.takeIf { slug -> models.any { it.slug == slug } }
                current.copy(selectionEpoch = if (chosen != latest.selectedModel) epoch() else current.selectionEpoch,
                    records = current.records.map { if (it.id == record.id) it.copy(models = models, selectedModel = chosen) else it })
            }
            models
        }
    }

    /** Must be called on a worker. Refresh serialization is shared across all facade instances. */
    fun access(cancelled: () -> Boolean): ChatGptAccess {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ChatGPT access must run on a worker." }
        val operation = generation.get()
        try {
            checkActive(operation, cancelled)
            val snapshot = store.read()
            val record = connected(snapshot)
            val model = record.selectedModel?.takeIf { slug -> record.models.any { it.slug == slug } }
                ?: throw ChatGptAuthFailure("Load your ChatGPT models and choose one in Providers first.")
            val tokens = credentials(record.id, record.epoch, snapshot.selectionEpoch, operation, cancelled)
            checkActive(operation, cancelled)
            val access = ChatGptAccess(record.id, model, tokens.access, record.epoch, snapshot.selectionEpoch)
            if (!isCurrent(access)) throw CancellationException()
            return access
        } catch (failure: Exception) { throw safeFailure(failure) }
    }

    /** A stale result can never follow a sign-out, account/model change or new authorization. */
    fun isCurrent(access: ChatGptAccess): Boolean = try {
        val snapshot = store.read()
        val record = snapshot.selectedRecord()
        snapshot.selectionEpoch == access.selectionEpoch && record?.id == access.accountId &&
            record.epoch == access.sessionEpoch && record.tokens?.planEnabled == true &&
            record.selectedModel == access.model && record.models.any { it.slug == access.model }
    } catch (_: Exception) { false }

    /** Stop local use immediately; remote revocation is bounded and never retains local tokens. */
    fun signOut(callback: (Result<Unit>) -> Unit) {
        val captured = try {
            var selected: ChatGptRecord? = null
            store.update { sessions ->
                selected = sessions.selectedRecord()
                val id = selected?.id ?: return@update sessions
                sessions.copy(selectionEpoch = epoch(), records = sessions.records.map {
                    if (it.id == id) it.copy(tokens = null, epoch = epoch(), models = emptyList(), selectedModel = null) else it
                })
            }
            selected
        } catch (_: Exception) {
            callback(Result.failure(ChatGptAuthFailure("Could not clear the saved ChatGPT connection. Please try again.")))
            return
        }
        async(callback) { operation ->
            if (captured?.tokens?.refresh != null) {
                // A refresh already in progress must finish its cleanup before this revocation.
                synchronized(REFRESH_LOCKS.computeIfAbsent(captured.id) { Any() }) {
                    try { revoke(captured.clientId, captured.tokens.refresh, operation) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        throw ChatGptAuthFailure("Signed out locally. Remote revocation was not confirmed. Disconnect Raw+ in ChatGPT Settings.")
                    }
                }
            }
            Unit
        }
    }

    /** Listener starts first. The Activity receives the URL only to pass it to the system browser. */
    internal fun beginSignIn(
        accountId: String?,
        ready: (Result<String>) -> Unit,
        complete: (Result<ChatGptAccount>) -> Unit
    ) {
        cancel()
        val operation = generation.get()
        if (closed) return
        job = worker.submit {
            var ownsAttempt = false
            var announced = false
            try {
                checkActive(operation)
                if (!SIGN_IN_ACTIVE.compareAndSet(false, true)) throw ChatGptAuthFailure("Another ChatGPT sign-in is already open. Finish or cancel it first.")
                ownsAttempt = true
                val host = store.ensureHost()
                val sessions = store.read()
                val existing = accountId?.let { id -> sessions.records.firstOrNull { it.id == id }
                    ?: throw ChatGptAuthFailure("That saved ChatGPT account is unavailable.") }
                if (existing == null && sessions.records.size >= ChatGptSessionCodec.MAX_ACCOUNTS)
                    throw ChatGptAuthFailure("This prototype supports up to ten saved ChatGPT registrations.")
                val server = ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
                    soTimeout = 500
                }
                listener = server
                checkActive(operation)
                val pending = ChatGptPending(ChatGptAuthProtocol.randomValue(), ChatGptAuthProtocol.randomValue(),
                    ChatGptAuthProtocol.randomValue(), "http://127.0.0.1:${server.localPort}/auth/callback",
                    existing?.clientId ?: pendingRegistrationClient ?: ChatGptAuthProtocol.DYNAMIC_CLIENT,
                    host, existing?.id, existing?.subject, existing?.email, existing?.tokens?.idToken, existing?.epoch)
                val authorize = ChatGptAuthProtocol.authorize(pending,
                    forceConsent = ChatGptAuthProtocol.needsPlanConsent(existing != null, existing?.tokens))
                post(operation) { ready(Result.success(authorize)) }
                announced = true
                val result = awaitCallback(server, pending, operation)
                server.close(); listener = null
                if (result.denied) throw ChatGptAuthFailure("ChatGPT sign-in was cancelled or permission was declined. Your other provider is unchanged.")
                if (existing == null) pendingRegistrationClient = result.clientId
                val json = http(ChatGptAuthProtocol.TOKEN, "POST", operation,
                    form = ChatGptAuthProtocol.codeGrant(result, pending))
                val tokens = ChatGptAuthProtocol.tokens(json, System.currentTimeMillis())
                val identity = validateIdentity(checkNotNull(tokens.idToken), result.clientId, pending.nonce, operation)
                if (existing != null && identity.subject != existing.subject)
                    throw ChatGptAuthFailure("ChatGPT returned a different account. Add it separately instead.")
                checkActive(operation)
                val account = saveSignIn(pending, result.clientId, identity, tokens)
                pendingRegistrationClient = null
                post(operation) { complete(Result.success(account)) }
            } catch (failure: Exception) {
                val safe = safeFailure(failure)
                post(operation) {
                    if (!announced) ready(Result.failure(safe))
                    complete(Result.failure(safe))
                }
            } finally {
                try { listener?.close() } catch (_: Exception) { }
                listener = null
                if (ownsAttempt) SIGN_IN_ACTIVE.set(false)
            }
        }
    }

    internal fun consumePlanWelcome(): Boolean = synchronized(WELCOME_LOCK) {
        val preferences = app.getSharedPreferences("chatgpt_welcome", Context.MODE_PRIVATE)
        if (preferences.getBoolean("plan_welcome_shown", false)) false else
            preferences.edit().putBoolean("plan_welcome_shown", true).commit()
    }

    fun cancel() {
        generation.incrementAndGet()
        job?.cancel(true)
        job = null
        try { listener?.close() } catch (_: Exception) { }
        synchronized(connectionLock) { connections.toList().forEach { it.disconnect() }; connections.clear() }
    }

    fun close() {
        closed = true
        cancel()
        worker.shutdownNow()
    }

    private fun credentials(
        id: String,
        expectedEpoch: String,
        selectionEpoch: String,
        operation: Long,
        cancelled: () -> Boolean
    ): ChatGptTokens = synchronized(REFRESH_LOCKS.computeIfAbsent(id) { Any() }) {
        checkActive(operation, cancelled)
        val sessions = store.read()
        val record = connected(sessions)
        if (record.id != id || record.epoch != expectedEpoch || sessions.selectionEpoch != selectionEpoch)
            throw CancellationException()
        val current = checkNotNull(record.tokens)
        val now = System.currentTimeMillis()
        if (current.expiresAt > now + 60_000L || (now < current.earliestRefreshAt && current.expiresAt > now + 5_000L))
            return@synchronized current
        if (now < current.earliestRefreshAt) throw ChatGptAuthFailure("ChatGPT cannot renew this session yet. Wait briefly and retry.")
        val refresh = current.refresh ?: throw ChatGptAuthFailure("Your ChatGPT session expired. Continue with ChatGPT again in Providers.")
        var replacement: ChatGptTokens? = null
        var installed = false
        try {
            val json = http(ChatGptAuthProtocol.TOKEN, "POST", operation,
                form = ChatGptAuthProtocol.refreshGrant(record.clientId, refresh), cancelled = cancelled)
            replacement = ChatGptAuthProtocol.tokens(json, System.currentTimeMillis(), current)
            if (JSONObject(json).has("id_token") && !JSONObject(json).isNull("id_token")) {
                val identity = validateIdentity(checkNotNull(replacement.idToken), record.clientId, null, operation)
                if (identity.subject != record.subject) throw ChatGptAuthFailure("ChatGPT returned a different account. Sign in again.")
            }
            // Persist a valid rotation even if this individual caller was cancelled just after receipt.
            // The epoch check still prevents a signed-out or replaced session being resurrected.
            val renewed = replacement
            store.update { latest ->
                val same = latest.records.firstOrNull { it.id == id }
                if (same?.epoch != expectedEpoch || same.tokens == null) return@update latest
                installed = true
                latest.copy(records = latest.records.map { if (it.id == id) it.copy(tokens = renewed) else it })
            }
            if (!installed) {
                throw CancellationException()
            }
            checkActive(operation, cancelled)
            if (!renewed.planEnabled) throw ChatGptAuthFailure("ChatGPT plan use is not enabled. Continue with ChatGPT again and approve plan access.")
            renewed
        } catch (failure: ChatGptHttpFailure) {
            if (failure.errorCode == "invalid_grant" || failure.errorCode == "invalid_client") {
                store.update { latest -> latest.copy(records = latest.records.map {
                    if (it.id == id && it.epoch == expectedEpoch) it.copy(tokens = null, epoch = epoch(), models = emptyList(), selectedModel = null) else it
                }) }
                throw ChatGptAuthFailure("Your ChatGPT session needs a new sign-in. Continue with ChatGPT in Providers.")
            }
            throw failure
        } catch (failure: Exception) {
            // A parsed rotation that fails identity validation must not leave an older session
            // appearing usable. If sign-out won the epoch race, its cleared record stays cleared.
            if (replacement != null && !installed) {
                store.update { latest -> latest.copy(records = latest.records.map {
                    if (it.id == id && it.epoch == expectedEpoch) it.copy(tokens = null, epoch = epoch(), models = emptyList(), selectedModel = null) else it
                }) }
                replacement.refresh?.let { token -> try { revoke(record.clientId, token, operation) } catch (_: Exception) { } }
            }
            throw failure
        }
    }

    private fun connected(sessions: ChatGptSessions): ChatGptRecord {
        val record = sessions.selectedRecord() ?: throw ChatGptAuthFailure("Connect a ChatGPT account in Providers first.")
        if (record.tokens == null) throw ChatGptAuthFailure("Continue with this ChatGPT account again in Providers.")
        if (!record.tokens.planEnabled) throw ChatGptAuthFailure("ChatGPT plan use is not enabled. Continue with ChatGPT again and approve plan access.")
        return record
    }

    private fun saveSignIn(pending: ChatGptPending, clientId: String, identity: ChatGptIdentity, tokens: ChatGptTokens): ChatGptAccount {
        var saved: ChatGptRecord? = null
        store.update { sessions ->
            val previous = if (pending.accountId == null) sessions.records.firstOrNull { it.clientId == clientId && it.subject == identity.subject }
                else sessions.records.firstOrNull { it.id == pending.accountId }
            if (pending.accountId != null && (previous == null || previous.epoch != pending.expectedEpoch || previous.clientId != clientId || previous.subject != identity.subject))
                throw ChatGptAuthFailure("This ChatGPT connection changed during sign-in. Start again.")
            if (previous == null && sessions.records.size >= ChatGptSessionCodec.MAX_ACCOUNTS)
                throw ChatGptAuthFailure("This prototype supports up to ten saved ChatGPT registrations.")
            val record = ChatGptRecord(previous?.id ?: epoch(), clientId, identity.subject,
                identity.email, identity.emailVerified, tokens, epoch())
            saved = record
            sessions.copy(selected = record.id, selectionEpoch = epoch(),
                records = sessions.records.filter { it.id != record.id } + record)
        }
        return checkNotNull(saved).publicAccount()
    }

    private fun validateIdentity(idToken: String, clientId: String, nonce: String?, operation: Long): ChatGptIdentity {
        val discovery = ChatGptAuthProtocol.discovery(http(ChatGptAuthProtocol.DISCOVERY, "GET", operation, maxBytes = 65_536))
        val jwks = http(discovery.first, "GET", operation, maxBytes = 262_144)
        return ChatGptAuthProtocol.validateIdToken(idToken, jwks, clientId, nonce, System.currentTimeMillis())
    }

    private fun revoke(clientId: String, token: String, operation: Long) {
        val form = ChatGptAuthProtocol.form(linkedMapOf("token" to token, "token_type_hint" to "refresh_token", "client_id" to clientId))
        fun attempt() {
            val endpoint = ChatGptAuthProtocol.discovery(http(ChatGptAuthProtocol.DISCOVERY, "GET", operation, maxBytes = 65_536)).second
            http(endpoint, "POST", operation, form = form)
        }
        try { attempt() }
        catch (failure: Exception) {
            if (failure is CancellationException || (failure is ChatGptHttpFailure && failure.status < 500)) throw failure
            checkActive(operation)
            Thread.sleep(500)
            attempt()
        }
    }

    private fun awaitCallback(server: ServerSocket, pending: ChatGptPending, operation: Long): ChatGptCallback {
        val deadline = System.nanoTime() + 10L * 60 * 1_000_000_000L
        var requests = 0
        while (System.nanoTime() < deadline && requests < 256) {
            checkActive(operation)
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            requests++
            socket.use {
                try {
                    require(socket.inetAddress.isLoopbackAddress)
                    socket.soTimeout = 2000
                    val target = readCallbackRequest(socket, server.localPort)
                    val callback = ChatGptAuthProtocol.callback(target, pending)
                    respond(socket, true)
                    return callback
                } catch (_: Exception) { try { respond(socket, false) } catch (_: Exception) { } }
            }
        }
        throw ChatGptAuthFailure("ChatGPT sign-in timed out. Return to Providers and start again.")
    }

    private fun readCallbackRequest(socket: Socket, port: Int): String {
        val input = socket.getInputStream()
        var total = 0
        val deadline = System.nanoTime() + 2_000_000_000L
        fun line(max: Int): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                require(b != -1 && ++total <= 24_576 && System.nanoTime() < deadline)
                if (b == 10) break
                require(bytes.size() < max && (b == 13 || b in 32..126))
                bytes.write(b)
            }
            val raw = bytes.toByteArray()
            require(raw.isNotEmpty() && raw.last() == 13.toByte() && raw.dropLast(1).none { it == 13.toByte() })
            return String(raw, 0, raw.size - 1, Charsets.US_ASCII)
        }
        val request = line(16_384).split(' ')
        require(request.size == 3 && request[0] == "GET" && request[2] == "HTTP/1.1")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line(4096)
            if (header.isEmpty()) break
            val split = header.indexOf(':')
            require(split > 0)
            val name = header.take(split).lowercase(java.util.Locale.ROOT)
            require(name.matches(Regex("[a-z0-9-]+")) && !headers.containsKey(name))
            headers[name] = header.substring(split + 1).trim()
            require(headers.size <= 48)
        }
        require(headers["host"] == "127.0.0.1:$port" && !headers.containsKey("transfer-encoding"))
        require(headers["content-length"] == null || headers["content-length"] == "0")
        return request[1]
    }

    private fun respond(socket: Socket, accepted: Boolean) {
        val body = if (accepted) "<!doctype html><html><head><meta name=referrer content=no-referrer><title>Return to Raw+</title></head><body><h1>Return to Raw+</h1><p>You can close this tab or press Back. Raw+ is checking your ChatGPT connection.</p></body></html>"
            else "<!doctype html><html><head><title>Invalid callback</title></head><body><p>This request was not accepted. Continue the sign-in in your original browser tab.</p></body></html>"
        val bytes = body.toByteArray(Charsets.US_ASCII)
        val status = if (accepted) "200 OK" else "400 Bad Request"
        val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    private fun http(
        endpoint: String,
        method: String,
        operation: Long,
        bearer: String? = null,
        form: String? = null,
        maxBytes: Int = ChatGptAuthProtocol.MAX_RESPONSE,
        cancelled: () -> Boolean = { false }
    ): String {
        checkActive(operation, cancelled)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        synchronized(connectionLock) { connections.add(connection) }
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (form != null) {
                val bytes = form.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 131_072)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setFixedLengthStreamingMode(bytes.size)
                checkActive(operation, cancelled)
                connection.outputStream.use { it.write(bytes) }
            }
            checkActive(operation, cancelled)
            val status = connection.responseCode
            if (status != 200) {
                val error = try { connection.errorStream?.use { readBounded(it, 65_536, operation, cancelled) } } catch (_: Exception) { null }
                val code = try { JSONObject(error.orEmpty()).opt("error") as? String } catch (_: Exception) { null }
                throw ChatGptHttpFailure(status, code?.takeIf { it in setOf("invalid_grant", "invalid_client", "temporarily_unavailable", "access_denied") })
            }
            return connection.inputStream.use { readBounded(it, maxBytes, operation, cancelled) }
        } finally {
            connection.disconnect()
            synchronized(connectionLock) { connections.remove(connection) }
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int, operation: Long, cancelled: () -> Boolean): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            checkActive(operation, cancelled)
            if (System.nanoTime() > deadline) throw java.io.IOException("ChatGPT read timed out")
            val count = input.read(buffer)
            if (count == -1) break
            if (bytes.size() + count > maxBytes) throw ChatGptAuthFailure("ChatGPT returned an oversized response. Try again later.")
            bytes.write(buffer, 0, count)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun checkActive(operation: Long, cancelled: () -> Boolean = { false }) {
        if (closed || generation.get() != operation || Thread.currentThread().isInterrupted || cancelled()) throw CancellationException()
    }

    private fun <T> async(callback: (Result<T>) -> Unit, action: (Long) -> T) {
        cancel()
        if (closed) return
        val operation = generation.get()
        job = worker.submit {
            val result = try { Result.success(action(operation)) } catch (failure: Exception) { Result.failure(safeFailure(failure)) }
            post(operation) { callback(result) }
        }
    }

    private fun post(operation: Long, action: () -> Unit) {
        main.post { if (!closed && generation.get() == operation) action() }
    }

    private fun <T> safeStore(action: () -> T): T = try { action() } catch (failure: Exception) {
        throw if (failure is ChatGptAuthFailure) failure else ChatGptAuthFailure("Could not save the ChatGPT account selection. Please try again.")
    }

    private fun safeFailure(failure: Exception): Exception = when (failure) {
        is ChatGptAuthFailure -> failure
        is CancellationException, is InterruptedException -> ChatGptAuthFailure("ChatGPT request cancelled. Your original text is still available.")
        is ChatGptHttpFailure -> ChatGptAuthFailure(when (failure.status) {
            400 -> if (failure.errorCode == "invalid_grant") "That ChatGPT sign-in code expired. Start a new sign-in." else "ChatGPT did not accept this request. Try signing in again."
            401 -> "Your ChatGPT session needs a new sign-in. Continue with ChatGPT in Providers."
            403 -> "ChatGPT denied access. Check plan permission or workspace policy in ChatGPT Settings."
            429 -> "ChatGPT usage is currently limited. Check ChatGPT Settings or try later. No other provider was used."
            in 500..599 -> "ChatGPT is temporarily unavailable. Try again later."
            else -> "ChatGPT returned an unexpected response. Try again later."
        })
        is java.io.IOException -> ChatGptAuthFailure("Could not reach ChatGPT. Check your connection and try again.")
        else -> ChatGptAuthFailure("Could not verify or save the ChatGPT connection. Start a new sign-in or try again later.")
    }

    private class ChatGptHttpFailure(val status: Int, val errorCode: String?) : Exception("ChatGPT HTTP request failed")

    companion object {
        private val REFRESH_LOCKS = ConcurrentHashMap<String, Any>()
        private val SIGN_IN_ACTIVE = AtomicBoolean()
        private val WELCOME_LOCK = Any()
        private fun epoch() = UUID.randomUUID().toString()
    }
}
