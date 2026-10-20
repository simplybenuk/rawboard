package uk.co.simplyben.rawplus

import org.json.JSONObject

/** Local generation uses the same editing rules and untouched source as the cloud path. */
object LlamaProtocol {
    const val CONTEXT_TOKENS = 8_192
    const val MAX_OUTPUT_TOKENS = 2_048
    const val TIMEOUT_MS = 180_000L
    private val controlTokens = listOf("<|im_start|>", "<|im_end|>", "<|endoftext|>")

    data class Prompt(val instructions: String, val input: String)

    fun prompt(request: EditRequest, skill: String): Prompt {
        // Refuse literal chat-boundary tokens instead of letting them become another message.
        // The source remains available in Raw and is never silently escaped or rewritten.
        require(controlTokens.none { request.transcript.contains(it) }) {
            "This draft contains model control tokens. Your original is safe. Use Raw."
        }
        return Prompt(EditProtocol.instructions(request, skill),
            JSONObject().put("transcript", request.transcript).toString())
    }

    /** Native generation returns only after an end-of-generation token, never a truncated result. */
    fun response(request: EditRequest, output: String): String {
        require(request.mode != EditMode.RAW) { "Raw text is never sent for AI editing." }
        val text = output.trim()
        check(text.isNotBlank() && text.length <= 65_536 && controlTokens.none { text.contains(it) }) {
            "The local model returned no usable text. Your original is safe. Use Raw or retry."
        }
        check(request.mode != EditMode.RAW_PLUS || LocalEditGuard.acceptsRawPlus(request.transcript, text)) {
            "The local model changed or dropped original words. Your original is safe. Use Raw or retry."
        }
        return text
    }
}
