package uk.co.simplyben.rawplus

/** Selection and credential scope rules, independent of Android storage and encryption. */
internal object ProviderPolicy {
    fun savedProvider(stored: String?, legacyUrl: String, hasLegacyToken: Boolean): EditingProvider =
        when (stored) {
            EditingProvider.OPENAI.name -> EditingProvider.OPENAI
            EditingProvider.BACKEND.name -> EditingProvider.BACKEND
            else -> if (legacyUrl.isNotBlank() && hasLegacyToken) EditingProvider.BACKEND else EditingProvider.OPENAI
        }

    fun selectedProvider(stored: String?, saved: EditingProvider): EditingProvider =
        when (stored) {
            // Retired providers need a new, explicit authorization and model choice. No API-key fallback.
            "NANO", "LLAMA" -> EditingProvider.CHATGPT
            else -> EditingProvider.entries.firstOrNull { it.name == stored } ?: saved
        }

    fun endpoint(provider: EditingProvider, value: String): String = when (provider) {
        EditingProvider.OPENAI -> EditingConfig.OPENAI_ENDPOINT
        EditingProvider.CHATGPT -> ""
        EditingProvider.BACKEND -> Endpoint.normalize(value)
    }

    fun mayReuseCredential(provider: EditingProvider, endpoint: String, saved: EditingConfig?): Boolean =
        provider != EditingProvider.CHATGPT && saved != null && saved.provider == provider && saved.url == endpoint

    fun validateCredential(provider: EditingProvider, credential: String) {
        require(provider != EditingProvider.CHATGPT) { "ChatGPT uses account authorization, not an API key." }
        val printable = credential.length >= 32 && credential.all { it.code in 33..126 }
        require(printable && (provider != EditingProvider.OPENAI || credential.startsWith("sk-"))) {
            if (provider == EditingProvider.OPENAI)
                "Enter an OpenAI API key starting with sk-, at least 32 characters with no spaces."
            else "Enter the backend access token, at least 32 printable characters with no spaces."
        }
    }
}
