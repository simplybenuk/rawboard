package uk.co.simplyben.rawplus

enum class EditingProvider(val label: String, val external: Boolean) {
    CHATGPT("ChatGPT plan", true),
    OPENAI("OpenAI", true),
    BACKEND("My own backend", true)
}

/** API credentials are user-entered. ChatGPT authorization is stored separately. */
class EditingConfig(val provider: EditingProvider, val url: String, val credential: String) {
    override fun toString() = "EditingConfig(provider=$provider, credential=<redacted>)"

    companion object {
        const val OPENAI_ENDPOINT = "https://api.openai.com/v1/responses"
        const val OPENAI_MODEL = "gpt-4.1-mini"
    }
}
