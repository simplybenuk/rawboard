package uk.co.simplyben.rawplus

enum class EditMode(val wireName: String, val label: String) {
    RAW("raw", "Raw"), RAW_PLUS("raw_plus", "Raw+"), POLISHED("polished", "Polished")
}

data class EditRequest(val id: Long, val mode: EditMode, val transcript: String)

/** In-memory draft. Every edit is bound to the original transcript and current request. */
class Draft {
    var original: String = ""
        private set
    /** UTF-16 offset in original, never between the two halves of a surrogate pair. */
    var cursor: Int = 0
        private set
    var partial: String = ""
    var unconfirmed: String = ""
    var mode = EditMode.RAW
        private set
    var edited: String? = null
        private set
    var pending = false
        private set
    private var generation = 0L

    val selectedText: String?
        get() = if (mode == EditMode.RAW) rawText() else edited

    fun append(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val next = listOf(original, trimmed).filter { it.isNotBlank() }.joinToString("\n\n")
        if (next.length > MAX_TRANSCRIPT) return false
        invalidate()
        original = next
        cursor = original.length
        partial = ""
        unconfirmed = ""
        return true
    }

    fun setCursor(offset: Int) {
        var next = offset.coerceIn(0, original.length)
        if (next > 0 && next < original.length &&
            original[next - 1].isHighSurrogate() && original[next].isLowSurrogate()) next--
        cursor = next
    }

    /** Inserts a confirmed paragraph. The cursor stops before the original suffix separator. */
    fun insert(text: String): Boolean {
        val insertion = insertion(text) ?: return true
        if (insertion.text.length > MAX_TRANSCRIPT) return false
        invalidate()
        original = insertion.text
        cursor = insertion.cursor
        partial = ""
        unconfirmed = ""
        return true
    }

    /** Projects unfinished speech at the insertion point without changing source or cursor. */
    fun previewWith(text: String): String = insertion(text)?.text ?: original

    /** Cursor matching previewWith, without committing the provisional words. */
    fun previewCursorWith(text: String): Int = insertion(text)?.cursor ?: cursor

    private data class Insertion(val text: String, val cursor: Int)

    private fun insertion(text: String): Insertion? {
        val chunk = text.trim()
        if (chunk.isEmpty()) return null
        val left = original.substring(0, cursor)
        val right = original.substring(cursor)
        val before = if (left.isEmpty()) "" else
            "\n".repeat((2 - left.takeLastWhile { it == '\n' }.length).coerceAtLeast(0))
        val after = if (right.isEmpty()) "" else
            "\n".repeat((2 - right.takeWhile { it == '\n' }.length).coerceAtLeast(0))
        return Insertion(left + before + chunk + after + right, left.length + before.length + chunk.length)
    }

    fun select(next: EditMode) {
        invalidate()
        mode = next
    }

    fun request(): EditRequest {
        check(mode != EditMode.RAW && original.isNotBlank())
        generation++
        pending = true
        edited = null
        return EditRequest(generation, mode, original)
    }

    fun accept(request: EditRequest, text: String): Boolean {
        if (!isCurrent(request) || text.isBlank()) return false
        edited = text.trim()
        pending = false
        return true
    }

    fun fail(request: EditRequest): Boolean {
        if (!isCurrent(request)) return false
        pending = false
        return true
    }

    fun invalidate() {
        generation++
        edited = null
        pending = false
    }

    fun clear() {
        invalidate()
        original = ""
        cursor = 0
        partial = ""
        unconfirmed = ""
        mode = EditMode.RAW
    }

    fun rawText(): String = original.split("\n\n").joinToString("\n\n") { paragraph ->
        // Add punctuation only. Never remove fillers, repetitions or uncertainty.
        if (paragraph.isNotBlank() && paragraph.last().isLetterOrDigit()) "$paragraph." else paragraph
    }

    private fun isCurrent(request: EditRequest) = pending && request.id == generation &&
        request.mode == mode && request.transcript == original

    companion object { const val MAX_TRANSCRIPT = 12_000 }
}
