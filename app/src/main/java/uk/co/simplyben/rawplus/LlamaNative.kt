package uk.co.simplyben.rawplus

/** No library is loaded until an explicit local edit creates a session. */
internal object LlamaNative {
    @Volatile private var loaded = false

    @Synchronized private fun load() {
        if (!loaded) {
            try {
                System.loadLibrary("rawplus_llama")
            } catch (_: UnsatisfiedLinkError) {
                throw IllegalStateException("The local model runtime could not load on this device.")
            }
            loaded = true
        }
    }

    fun createSession(): Long {
        load()
        return nativeCreateSession()
    }

    fun generate(
        session: Long,
        modelPath: String,
        instructions: String,
        input: String,
        contextTokens: Int,
        threads: Int,
        maxOutputTokens: Int,
        timeoutMs: Long,
    ): String = nativeGenerate(
        session, modelPath, instructions, input, contextTokens, threads, maxOutputTokens, timeoutMs,
    )

    fun cancel(session: Long) {
        if (loaded && session != 0L) nativeCancel(session)
    }

    fun destroySession(session: Long) {
        if (loaded && session != 0L) nativeDestroySession(session)
    }

    private external fun nativeCreateSession(): Long
    private external fun nativeGenerate(
        session: Long,
        modelPath: String,
        instructions: String,
        input: String,
        contextTokens: Int,
        threads: Int,
        maxOutputTokens: Int,
        timeoutMs: Long,
    ): String
    private external fun nativeCancel(session: Long)
    private external fun nativeDestroySession(session: Long)
}
