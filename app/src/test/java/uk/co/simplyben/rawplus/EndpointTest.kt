package uk.co.simplyben.rawplus

import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun `remote editing requires TLS but USB development permits loopback`() {
        assertEquals("https://example.com", Endpoint.normalize(" https://example.com/ "))
        assertEquals("http://127.0.0.1:8787", Endpoint.normalize("http://127.0.0.1:8787/"))
        assertEquals("http://localhost:8787", Endpoint.normalize("http://localhost:8787"))
        listOf("http://example.com", "http://192.168.1.2:8787", "http://127.0.0.1.evil.example",
            "https://user:pass@example.com", "https://example.com?a=secret", "https://example.com#x",
            "file:///tmp/backend", "https://example.com:99999").forEach { value ->
            assertThrows("must reject $value", IllegalArgumentException::class.java) { Endpoint.normalize(value) }
        }
    }
}
