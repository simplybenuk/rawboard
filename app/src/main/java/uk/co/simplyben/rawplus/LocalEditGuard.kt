package uk.co.simplyben.rawplus

import java.util.Locale

/** A conservative check for local Raw+, not a semantic guarantee or a replacement for review. */
internal object LocalEditGuard {
    private const val MAX_OUTPUT_CHARS = 65_536
    private const val MAX_TOKENS = 6_000
    private const val MAX_REPEAT_TOKENS = 8
    private val hesitations = setOf("um", "uh", "erm", "er")
    private val capitalizedHesitations = setOf("Um", "Uh", "Erm", "Er")
    private val tokenPattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*(?:'[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*)?|\\p{S}|[%&/\\-]")
    private data class Token(val value: String, val hesitation: Boolean)

    fun acceptsRawPlus(source: String, output: String): Boolean {
        if (source.isBlank() || source.length > Draft.MAX_TRANSCRIPT ||
            output.isBlank() || output.length > MAX_OUTPUT_CHARS) return false
        val original = tokens(source) ?: return false
        val edited = tokens(output) ?: return false
        // Folding repetitions must not let the model invent another "not", filler or phrase.
        var cursor = 0
        for (token in edited) {
            while (cursor < original.size && original[cursor].value != token.value) cursor++
            if (cursor == original.size) return false
            cursor++
        }
        return canonicalTokens(original) == canonicalTokens(edited)
    }

    private fun tokens(text: String): List<Token>? {
        val normalized = text.replace('\u2019', '\'').replace('\u2018', '\'')
        val result = ArrayList<Token>()
        var count = 0
        for (match in tokenPattern.findAll(normalized)) {
            if (++count > MAX_TOKENS) return null
            val token = match.value
            // Uppercase ER/ERM can be acronyms. Never silently discard those as hesitation.
            result.add(Token(token.lowercase(Locale.ROOT),
                token in hesitations || token in capitalizedHesitations))
        }
        return result
    }

    private fun canonicalTokens(tokens: List<Token>): List<String> {
        val result = ArrayList<String>()
        for (token in tokens) {
            if (token.hesitation) continue
            result.add(token.value)
            collapseRepeatedSuffix(result)
        }
        return result
    }

    /**
     * Scan left to right, collapsing the shortest exactly repeated adjacent suffix first.
     * Width is capped at eight tokens, so this remains linear in the bounded token count.
     * Both versions follow this same rule. Meaningful emphatic repetition can also collapse,
     * and punctuation can change meaning without changing these tokens. Review is still needed.
     */
    private fun collapseRepeatedSuffix(tokens: ArrayList<String>) {
        while (true) {
            var repeatedWidth = 0
            for (width in 1..minOf(MAX_REPEAT_TOKENS, tokens.size / 2)) {
                val start = tokens.size - width * 2
                var same = true
                for (offset in 0 until width) {
                    if (tokens[start + offset] != tokens[start + width + offset]) {
                        same = false
                        break
                    }
                }
                if (same) {
                    repeatedWidth = width
                    break
                }
            }
            if (repeatedWidth == 0) return
            repeat(repeatedWidth) { tokens.removeAt(tokens.lastIndex) }
        }
    }
}
