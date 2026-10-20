package uk.co.simplyben.rawplus

import org.junit.Assert.*
import org.junit.Test

class DraftTest {
    @Test fun `new and cleared drafts use raw without an AI request`() {
        val draft = Draft()
        assertEquals(EditMode.RAW, draft.mode)
        draft.append("um I guess this works")
        assertEquals("um I guess this works.", draft.selectedText)
        assertFalse(draft.pending)
        assertThrows(IllegalStateException::class.java) { draft.request() }

        draft.select(EditMode.POLISHED)
        val request = draft.request()
        assertTrue(draft.accept(request, "This works."))
        draft.partial = "unfinished"
        draft.unconfirmed = "check this"
        draft.clear()

        assertEquals(EditMode.RAW, draft.mode)
        assertEquals("", draft.original)
        assertEquals("", draft.partial)
        assertEquals("", draft.unconfirmed)
        assertNull(draft.edited)
        assertFalse(draft.pending)
        draft.append("um another thought")
        assertEquals("um another thought.", draft.selectedText)
        assertThrows(IllegalStateException::class.java) { draft.request() }
    }

    @Test fun `every mode regenerates from original speech not previous edit`() {
        val draft = Draft()
        draft.append("I guess um I guess this is kind of useful")
        draft.select(EditMode.RAW_PLUS)
        val rawPlus = draft.request()
        assertTrue(draft.accept(rawPlus, "I guess this is kind of useful."))
        draft.select(EditMode.POLISHED)
        val polished = draft.request()
        assertEquals("I guess um I guess this is kind of useful", polished.transcript)
        assertNotEquals("I guess this is kind of useful.", polished.transcript)
        draft.select(EditMode.RAW)
        assertEquals("I guess um I guess this is kind of useful.", draft.selectedText)
    }

    @Test fun `late response after mode change never becomes insertable`() {
        val draft = Draft().apply { append("first thought"); select(EditMode.RAW_PLUS) }
        val first = draft.request()
        draft.select(EditMode.POLISHED)
        val second = draft.request()
        assertFalse(draft.accept(first, "old text"))
        assertTrue(draft.accept(second, "current text"))
        assertEquals("current text", draft.selectedText)
    }

    @Test fun `late response cannot populate a cleared session even with identical source and mode`() {
        val draft = Draft().apply { append("private thought"); select(EditMode.RAW_PLUS) }
        val old = draft.request()
        draft.clear()
        assertEquals(EditMode.RAW, draft.mode)
        assertFalse(draft.accept(old, "private thought edited"))
        assertFalse(draft.fail(old))
        draft.append("private thought")
        assertEquals("private thought.", draft.selectedText)
        draft.select(EditMode.RAW_PLUS)
        val current = draft.request()
        assertFalse(draft.accept(old, "private thought edited"))
        assertFalse(draft.fail(old))
        assertTrue(draft.pending)
        assertEquals("private thought", draft.original)
        assertNull(draft.selectedText)
        assertTrue(draft.accept(current, "Current edit."))
        assertEquals("Current edit.", draft.selectedText)
    }

    @Test fun `appending speech invalidates edited text and older failures`() {
        val draft = Draft().apply { append("first thought"); select(EditMode.RAW_PLUS) }
        val old = draft.request()
        draft.append("and another")
        val current = draft.request()
        assertFalse(draft.fail(old))
        assertTrue(draft.pending)
        assertEquals("first thought\n\nand another", current.transcript)
        assertTrue(draft.fail(current))
        assertFalse(draft.pending)
        draft.select(EditMode.RAW)
        assertEquals("first thought.\n\nand another.", draft.selectedText)
    }

    @Test fun `recognition hypotheses are provisional and never raw source`() {
        val draft = Draft()
        draft.partial = "I think th"
        draft.partial = "I think this works"
        assertEquals("", draft.original)
        draft.unconfirmed = draft.partial
        draft.partial = ""
        assertEquals("", draft.selectedText)
        draft.append(draft.unconfirmed)
        assertEquals("I think this works", draft.original)
        assertEquals("", draft.unconfirmed)
    }

    @Test fun `raw mode preserves fillers repeats expressions and existing punctuation`() {
        val original = "ooh I guess I guess it's kind of useful, isn't it?\n\num literally yeah"
        val draft = Draft().apply { append(original); select(EditMode.RAW) }
        assertEquals("ooh I guess I guess it's kind of useful, isn't it?\n\num literally yeah.", draft.selectedText)
        assertEquals(original, draft.original)
    }

    @Test fun `oversized append leaves existing transcript available`() {
        val draft = Draft().apply { append("confirmed") }
        assertFalse(draft.append("x".repeat(Draft.MAX_TRANSCRIPT)))
        assertEquals("confirmed", draft.original)
    }

    @Test fun `cursor inserts paragraphs at beginning middle and end without replacing source`() {
        val draft = Draft().apply { append("first\n\nlast") }
        draft.setCursor(0)
        assertTrue(draft.insert("intro"))
        assertEquals("intro\n\nfirst\n\nlast", draft.original)
        assertEquals(5, draft.cursor)

        draft.setCursor(draft.original.indexOf("last"))
        assertTrue(draft.insert("middle"))
        assertEquals("intro\n\nfirst\n\nmiddle\n\nlast", draft.original)
        assertEquals(draft.original.indexOf("middle") + "middle".length, draft.cursor)

        draft.setCursor(Int.MAX_VALUE)
        assertTrue(draft.insert("ending"))
        assertEquals("intro\n\nfirst\n\nmiddle\n\nlast\n\nending", draft.original)
        assertEquals(draft.original.length, draft.cursor)
    }

    @Test fun `middle insertion adds boundaries while preserving both original slices`() {
        val draft = Draft().apply { append("leftright"); setCursor(4) }
        assertTrue(draft.insert("new"))
        assertEquals("left\n\nnew\n\nright", draft.original)
        assertEquals("left\n\nnew".length, draft.cursor)
        assertTrue(draft.insert("next segment"))
        assertEquals("left\n\nnew\n\nnext segment\n\nright", draft.original)
        assertEquals(draft.original.indexOf("\n\nright"), draft.cursor)
    }

    @Test fun `paragraph insertion completes boundaries before between and after existing newlines`() {
        for (offset in 4..6) {
            val draft = Draft().apply { append("left\n\nright"); setCursor(offset) }
            assertEquals("left\n\nnew\n\nright", draft.previewWith("new"))
            assertEquals("left\n\nnew".length, draft.previewCursorWith("new"))
            assertEquals("left\n\nright", draft.original)
            assertEquals(offset, draft.cursor)
            assertTrue(draft.insert("new"))
            assertEquals("left\n\nnew\n\nright", draft.original)
            assertEquals("left\n\nnew".length, draft.cursor)
            assertTrue(draft.insert("next segment"))
            assertEquals("left\n\nnew\n\nnext segment\n\nright", draft.original)
            assertEquals(draft.original.indexOf("\n\nright"), draft.cursor)
        }
    }

    @Test fun `insertion preserves longer existing newline runs on each original slice`() {
        val draft = Draft().apply { append("left\n\n\nright"); setCursor(4) }
        assertTrue(draft.insert("new"))
        assertEquals("left\n\nnew\n\n\nright", draft.original)
        draft.setCursor(draft.original.indexOf("right"))
        assertTrue(draft.insert("another"))
        assertEquals("left\n\nnew\n\n\nanother\n\nright", draft.original)
    }

    @Test fun `provisional insertion leaves original cursor and edit request untouched`() {
        val draft = Draft().apply { append("beforeafter"); setCursor(6); select(EditMode.RAW_PLUS) }
        val request = draft.request()
        assertEquals("before\n\nunfinished\n\nafter", draft.previewWith("unfinished"))
        assertEquals("before\n\nunfinished".length, draft.previewCursorWith("unfinished"))
        assertEquals("before\n\nrevised words\n\nafter", draft.previewWith("revised words"))
        assertEquals("before\n\nrevised words".length, draft.previewCursorWith("revised words"))
        assertEquals("beforeafter", draft.previewWith(" \n\t "))
        assertEquals(6, draft.previewCursorWith(" \n\t "))
        assertEquals("beforeafter", draft.original)
        assertEquals(6, draft.cursor)
        assertTrue(draft.pending)
        assertTrue(draft.accept(request, "An edit."))
    }

    @Test fun `failed or blank insert leaves all draft state stable`() {
        val draft = Draft().apply {
            append("beforeafter"); setCursor(6); select(EditMode.POLISHED)
            partial = "unfinished"
            unconfirmed = "check this"
        }
        val request = draft.request()
        assertFalse(draft.insert("x".repeat(Draft.MAX_TRANSCRIPT)))
        assertTrue(draft.insert(" \n\t "))
        assertEquals("beforeafter", draft.original)
        assertEquals(6, draft.cursor)
        assertEquals("unfinished", draft.partial)
        assertEquals("check this", draft.unconfirmed)
        assertEquals(EditMode.POLISHED, draft.mode)
        assertTrue(draft.pending)
        assertTrue(draft.accept(request, "Still current."))
    }

    @Test fun `cursor clamps safely around emoji and append and clear reset its position`() {
        val draft = Draft().apply { append("a\uD83D\uDE00b") }
        draft.setCursor(2)
        assertEquals(1, draft.cursor)
        assertTrue(draft.insert("hello"))
        assertEquals("a\n\nhello\n\n\uD83D\uDE00b", draft.original)
        draft.setCursor(-4)
        assertEquals(0, draft.cursor)
        assertTrue(draft.append("tail"))
        assertEquals(draft.original.length, draft.cursor)
        draft.clear()
        assertEquals(0, draft.cursor)
    }

    @Test fun `confirmed cursor insertion invalidates stale AI without changing selected mode`() {
        val draft = Draft().apply { append("beforeafter"); setCursor(6); select(EditMode.RAW_PLUS) }
        val old = draft.request()
        assertTrue(draft.insert("um I guess"))
        assertEquals(EditMode.RAW_PLUS, draft.mode)
        assertFalse(draft.pending)
        assertNull(draft.edited)
        assertFalse(draft.accept(old, "Obsolete edit."))
        assertFalse(draft.fail(old))
        val current = draft.request()
        assertEquals("before\n\num I guess\n\nafter", current.transcript)
    }
}
