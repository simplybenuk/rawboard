package uk.co.simplyben.rawplus

import org.json.JSONObject
import java.io.Reader

/** The documented ChatGPT-plan Responses route, separate from API-key billing. */
object ChatGptProtocol {
    const val ENDPOINT = "https://api.openai.com/v1/responses"
    private const val MAX_TEXT = 65_536
    private const val MAX_EVENT = 1_048_576
    private const val MAX_STREAM = 4_194_304

    fun body(request: EditRequest, skill: String, model: String): String {
        require(ChatGptAuthProtocol.validModel(model)) {
            "Choose an available model in Providers first."
        }
        val instructions = EditProtocol.instructions(request, skill)
        val original = JSONObject().put("transcript", request.transcript).toString()
        // This preview route requires array input and streaming, and rejects max_output_tokens.
        return JSONObject().put("model", model).put("store", false).put("stream", true)
            .put("instructions", instructions)
            .put("input", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", original)))
            .toString()
    }

    /** Never promote deltas to a draft. The complete terminal response is authoritative. */
    fun readStream(reader: Reader, cancelled: () -> Boolean = { false }): String {
        val input = reader.buffered()
        var total = 0
        val data = StringBuilder()
        val deltas = StringBuilder()
        var sawDelta = false
        var eventName = ""
        while (true) {
            check(!cancelled()) { "ChatGPT editing cancelled. Your original is safe." }
            val line = StringBuilder()
            var eof = false
            while (true) {
                val ch = input.read()
                if (ch == -1) { eof = true; break }
                check(++total <= MAX_STREAM) { invalid() }
                if (ch == '\n'.code) break
                if (ch != '\r'.code) line.append(ch.toChar())
                check(line.length <= MAX_EVENT) { invalid() }
            }
            // An unterminated SSE frame at EOF is an interrupted stream, even if it looks like JSON.
            if (eof) error("ChatGPT's stream ended before completion. Your original is safe. Use Raw or retry.")
            val text = line.toString()
            if (text.isEmpty()) {
                if (data.isEmpty()) { eventName = ""; continue }
                val jsonText = data.toString()
                data.setLength(0)
                if (jsonText == "[DONE]") error("ChatGPT's stream ended before completion. Your original is safe.")
                val event = try { JSONObject(jsonText) } catch (_: Exception) { error(invalid()) }
                val type = event.opt("type") as? String ?: error(invalid())
                if (eventName.isNotEmpty() && eventName != "message" && eventName != type) error(invalid())
                eventName = ""
                when (type) {
                    "response.output_text.delta" -> {
                        val delta = event.opt("delta") as? String ?: error(invalid())
                        check(delta.length <= MAX_TEXT - deltas.length) { invalid() }
                        deltas.append(delta)
                        sawDelta = true
                    }
                    "response.completed" -> {
                        val result = completed(event.optJSONObject("response") ?: error(invalid()))
                        check(!sawDelta || deltas.toString().trim() == result) { invalid() }
                        check(!cancelled()) { "ChatGPT editing cancelled. Your original is safe." }
                        return result
                    }
                    "response.failed" -> {
                        val code = event.optJSONObject("response")?.optJSONObject("error")?.opt("code") as? String
                        error(errorForCode(code))
                    }
                    "error" -> error(errorForCode(event.opt("code") as? String
                        ?: event.optJSONObject("error")?.opt("code") as? String))
                    "response.incomplete", "response.refusal.delta", "response.refusal.done" -> error(
                        "ChatGPT did not complete the edit. Your original is safe. Use Raw or retry.")
                    // Creation, progress, reasoning and item events contain no accepted preview.
                    else -> Unit
                }
            } else if (text.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(text.substring(5).removePrefix(" "))
                check(data.length <= MAX_EVENT) { invalid() }
            } else if (text.startsWith("event:")) {
                check(eventName.isEmpty()) { invalid() }
                eventName = text.substring(6).trim()
            }
        }
    }

    private fun completed(response: JSONObject): String {
        check(response.opt("status") == "completed" && response.isNull("error") &&
            response.isNull("incomplete_details")) { invalid() }
        val items = response.optJSONArray("output") ?: error(invalid())
        val text = StringBuilder()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: error(invalid())
            if (item.opt("type") == "reasoning") continue
            check(item.opt("type") == "message" && item.opt("role") == "assistant" &&
                (item.isNull("status") || item.opt("status") == "completed")) { invalid() }
            val parts = item.optJSONArray("content") ?: error(invalid())
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: error(invalid())
                check(part.opt("type") == "output_text") { invalid() }
                val value = part.opt("text") as? String ?: error(invalid())
                check(value.length <= MAX_TEXT - text.length) { invalid() }
                text.append(value)
            }
        }
        return text.toString().trim().also { check(it.isNotBlank()) { invalid() } }
    }

    fun httpError(status: Int, body: String): String {
        val code = try { JSONObject(body).optJSONObject("error")?.opt("code") as? String } catch (_: Exception) { null }
        if (code?.startsWith("subscription_sharing_") == true) return errorForCode(code)
        return when (status) {
            401 -> "ChatGPT sign-in expired or was revoked. Sign in again in Providers. Your original is safe."
            403, 404 -> "This ChatGPT account cannot use the selected model. Check Providers and Manage usage. Your original is safe."
            429 -> "ChatGPT usage is limited right now. Open Manage usage in Providers. Your original is safe."
            else -> "ChatGPT editing failed. Your original is safe. Use Raw or retry."
        }
    }

    private fun errorForCode(code: String?): String = when (code) {
        "subscription_sharing_usage_limit_exceeded" ->
            "ChatGPT plan or app usage limit reached. Open Manage usage in Providers. Your original is safe."
        "subscription_sharing_usage_unavailable" ->
            "ChatGPT plan use is unavailable for this app or account. Open Manage usage in Providers. Your original is safe."
        else -> "ChatGPT did not complete the edit. Your original is safe. Use Raw or retry."
    }

    private fun invalid() = "ChatGPT returned an incomplete or invalid edit. Your original is safe. Use Raw or retry."
}
