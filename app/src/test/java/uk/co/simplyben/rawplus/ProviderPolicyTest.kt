package uk.co.simplyben.rawplus

import org.junit.Assert.*
import org.junit.Test

class ProviderPolicyTest {
    private val backend = EditingConfig(EditingProvider.BACKEND, "https://example.com", "test-backend-token-000000000000000")

    @Test fun `legacy backend without provider field retains its credential owner`() {
        assertEquals(EditingProvider.BACKEND, ProviderPolicy.savedProvider(null, "https://example.com", true))
        assertEquals(EditingProvider.OPENAI, ProviderPolicy.savedProvider(null, "", true))
        assertEquals(EditingProvider.OPENAI, ProviderPolicy.savedProvider(null, "https://example.com", false))
    }

    @Test fun `explicit API provider takes precedence over legacy inference`() {
        assertEquals(EditingProvider.OPENAI,
            ProviderPolicy.savedProvider("OPENAI", "https://example.com", true))
        assertEquals(EditingProvider.BACKEND, ProviderPolicy.savedProvider("BACKEND", "", false))
    }

    @Test fun `ChatGPT selection does not change saved API credential ownership`() {
        val saved = ProviderPolicy.savedProvider("OPENAI", EditingConfig.OPENAI_ENDPOINT, true)
        assertEquals(EditingProvider.CHATGPT, ProviderPolicy.selectedProvider("CHATGPT", saved))
        assertEquals(EditingProvider.OPENAI, saved)
        assertEquals(saved, ProviderPolicy.selectedProvider(null, saved))
        assertEquals(saved, ProviderPolicy.selectedProvider("unknown", saved))
    }

    @Test fun `retired local providers require ChatGPT setup without a silent API key fallback`() {
        for (retired in listOf("NANO", "LLAMA")) {
            for (saved in listOf(EditingProvider.OPENAI, EditingProvider.BACKEND)) {
                assertEquals(EditingProvider.CHATGPT, ProviderPolicy.selectedProvider(retired, saved))
            }
        }
    }

    @Test fun `ChatGPT is external but never owns the API credential slot`() {
        assertTrue(EditingProvider.CHATGPT.external)
        assertEquals(EditingProvider.OPENAI, ProviderPolicy.savedProvider("CHATGPT", "", false))
        assertEquals(EditingProvider.BACKEND,
            ProviderPolicy.savedProvider("CHATGPT", "https://example.com", true))
    }

    @Test fun `ChatGPT has no API endpoint and OpenAI cannot adopt a supplied endpoint`() {
        assertEquals("", ProviderPolicy.endpoint(EditingProvider.CHATGPT, "https://untrusted.example"))
        assertEquals(EditingConfig.OPENAI_ENDPOINT,
            ProviderPolicy.endpoint(EditingProvider.OPENAI, "https://untrusted.example"))
    }

    @Test fun `blank credentials can reuse only the same API provider and normalized endpoint`() {
        val endpoint = ProviderPolicy.endpoint(EditingProvider.BACKEND, " https://example.com/ ")
        assertTrue(ProviderPolicy.mayReuseCredential(EditingProvider.BACKEND, endpoint, backend))
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.OPENAI, endpoint, backend))
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.BACKEND, "https://another.example", backend))
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.BACKEND, endpoint, null))
    }

    @Test fun `ChatGPT never reuses or accepts an API key even though it sends text externally`() {
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.CHATGPT, backend.url, backend))
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.CHATGPT, "",
            EditingConfig(EditingProvider.CHATGPT, "", "sk-test-not-a-real-key-0000000000000")))
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPolicy.validateCredential(EditingProvider.CHATGPT, "sk-test-not-a-real-key-0000000000000")
        }
    }

    @Test fun `credential validation stays scoped to its API provider`() {
        ProviderPolicy.validateCredential(EditingProvider.BACKEND, backend.credential)
        ProviderPolicy.validateCredential(EditingProvider.OPENAI, "sk-test-not-a-real-key-0000000000000")
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPolicy.validateCredential(EditingProvider.OPENAI, backend.credential)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPolicy.validateCredential(EditingProvider.BACKEND, "x".repeat(32) + " ")
        }
    }

    @Test fun `configuration string redacts endpoint and credential`() {
        val config = EditingConfig(EditingProvider.OPENAI, "https://secret.example", "secret-key")
        assertFalse(config.toString().contains("secret"))
        assertTrue(config.toString().contains("<redacted>"))
    }
}
