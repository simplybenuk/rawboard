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

    @Test fun `explicit cloud provider takes precedence over legacy inference`() {
        assertEquals(EditingProvider.OPENAI,
            ProviderPolicy.savedProvider("OPENAI", "https://example.com", true))
        assertEquals(EditingProvider.BACKEND, ProviderPolicy.savedProvider("BACKEND", "", false))
    }

    @Test fun `separate selection allows llama cpp without changing cloud credential owner`() {
        val cloud = ProviderPolicy.savedProvider("OPENAI", EditingConfig.OPENAI_ENDPOINT, true)
        assertEquals(EditingProvider.LLAMA, ProviderPolicy.selectedProvider("LLAMA", cloud))
        assertEquals(EditingProvider.OPENAI, cloud)
        assertEquals(cloud, ProviderPolicy.selectedProvider(null, cloud))
        assertEquals(cloud, ProviderPolicy.selectedProvider("unknown", cloud))
    }

    @Test fun `retired local provider migrates locally without selecting a saved cloud provider`() {
        for (saved in listOf(EditingProvider.OPENAI, EditingProvider.BACKEND)) {
            assertEquals(EditingProvider.LLAMA, ProviderPolicy.selectedProvider("NANO", saved))
        }
    }

    @Test fun `llama cpp has no endpoint and OpenAI cannot adopt a supplied endpoint`() {
        assertEquals("", ProviderPolicy.endpoint(EditingProvider.LLAMA, "https://untrusted.example"))
        assertEquals(EditingConfig.OPENAI_ENDPOINT,
            ProviderPolicy.endpoint(EditingProvider.OPENAI, "https://untrusted.example"))
    }

    @Test fun `blank credentials can reuse only the same provider and normalized endpoint`() {
        val endpoint = ProviderPolicy.endpoint(EditingProvider.BACKEND, " https://example.com/ ")
        assertTrue(ProviderPolicy.mayReuseCredential(EditingProvider.BACKEND, endpoint, backend))
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.OPENAI, endpoint, backend))
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.BACKEND, "https://another.example", backend))
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.BACKEND, endpoint, null))
        assertFalse(ProviderPolicy.mayReuseCredential(EditingProvider.LLAMA, "", EditingConfig(EditingProvider.LLAMA, "", "")))
    }

    @Test fun `credential validation stays scoped to the external provider`() {
        ProviderPolicy.validateCredential(EditingProvider.BACKEND, backend.credential)
        ProviderPolicy.validateCredential(EditingProvider.OPENAI, "sk-test-not-a-real-key-0000000000000")
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPolicy.validateCredential(EditingProvider.OPENAI, backend.credential)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPolicy.validateCredential(EditingProvider.BACKEND, "x".repeat(32) + " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPolicy.validateCredential(EditingProvider.LLAMA, "sk-test-not-a-real-key-0000000000000")
        }
    }

    @Test fun `configuration string redacts endpoint and credential`() {
        val config = EditingConfig(EditingProvider.OPENAI, "https://secret.example", "secret-key")
        assertFalse(config.toString().contains("secret"))
        assertTrue(config.toString().contains("<redacted>"))
    }
}
