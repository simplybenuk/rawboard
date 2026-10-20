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
            "NANO" -> EditingProvider.LLAMA // A retired local provider must never silently choose a cloud one.
            else -> EditingProvider.entries.firstOrNull { it.name == stored } ?: saved
        }

    fun endpoint(provider: EditingProvider, value: String): String = when (provider) {
        EditingProvider.OPENAI -> EditingConfig.OPENAI_ENDPOINT
        EditingProvider.LLAMA -> ""
        EditingProvider.BACKEND -> Endpoint.normalize(value)
    }

    fun mayReuseCredential(provider: EditingProvider, endpoint: String, saved: EditingConfig?): Boolean =
        provider.external && saved != null && saved.provider == provider && saved.url == endpoint

    fun validateCredential(provider: EditingProvider, credential: String) {
        require(provider.external) { "On-device editing does not use an API key." }
        val printable = credential.length >= 32 && credential.all { it.code in 33..126 }
        require(printable && (provider != EditingProvider.OPENAI || credential.startsWith("sk-"))) {
            if (provider == EditingProvider.OPENAI)
                "Enter an OpenAI API key starting with sk-, at least 32 characters with no spaces."
            else "Enter the backend access token, at least 32 printable characters with no spaces."
        }
    }
}
