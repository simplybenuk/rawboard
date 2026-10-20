package uk.co.simplyben.rawplus

enum class EditingProvider(val label: String, val external: Boolean) {
    OPENAI("OpenAI", true),
    LLAMA("llama.cpp on this device", false),
    BACKEND("My own backend", true)
}

/** Credentials are user-entered on this device, never compiled into the app. */
class EditingConfig(val provider: EditingProvider, val url: String, val credential: String) {
    override fun toString() = "EditingConfig(provider=$provider, credential=<redacted>)"

    companion object {
        const val OPENAI_ENDPOINT = "https://api.openai.com/v1/responses"
        const val OPENAI_MODEL = "gpt-4.1-mini"
    }
}
