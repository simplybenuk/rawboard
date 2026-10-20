package uk.co.simplyben.rawplus

import java.net.URI

object Endpoint {
    fun normalize(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid HTTPS backend URL.")
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "Use a URL without login details, query or fragment." }
        val local = uri.host == "127.0.0.1" || uri.host == "localhost"
        require(uri.scheme == "https" || (uri.scheme == "http" && local)) {
            "Remote backends require HTTPS. HTTP is allowed only for localhost over USB."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid port." }
        return uri.toASCIIString().trimEnd('/')
    }
}
