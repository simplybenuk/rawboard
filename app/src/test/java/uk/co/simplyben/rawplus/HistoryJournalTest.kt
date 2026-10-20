package uk.co.simplyben.rawplus

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryJournalTest {
    private val now = 1_800_000_000_000L

    private fun entry(
        id: String = "first",
        session: String = "session-a",
        event: String = "dictated",
        mode: EditMode = EditMode.RAW,
        timestamp: Long = now,
        original: String = "I guess, um, this is my thought.",
        text: String = original
    ) = HistoryEntry(id, timestamp, session, event, original, mode, text, "rawplus-v2")

    @Test fun roundTripPreservesOriginalAndResultAsSeparateExactValues() {
        val original = "I guess, um, the \"test\" is useful.\n\nMaybe. 🙂"
        val entries = listOf(
            entry(original = original),
            entry("edited", event = "edited", mode = EditMode.RAW_PLUS,
                original = original, text = "I guess the \"test\" is useful.\n\nMaybe. 🙂"),
            entry("inserted", event = "inserted", mode = EditMode.POLISHED,
                original = original, text = "I guess the test is useful. Maybe.")
        )

        assertEquals(entries, HistoryJournal.decode(HistoryJournal.encode(entries)))
        assertEquals(original, HistoryJournal.decode(HistoryJournal.encode(entries))[1].original)
    }

    @Test fun consecutiveRawDictationReplacesOnlyThePreviousSnapshotInTheSameSession() {
        val first = entry(original = "First thought.")
        val next = entry("next", original = "First thought. Another paragraph.")

        assertEquals(listOf(next), HistoryJournal.append(listOf(first), next, now))
        val otherSession = next.copy(sessionId = "session-b")
        assertEquals(listOf(first, otherSession), HistoryJournal.append(listOf(first), otherSession, now))

        val ai = entry("edit", event = "edited", mode = EditMode.RAW_PLUS, text = "A prior edit.")
        assertEquals(listOf(first, ai, next), HistoryJournal.append(listOf(first, ai), next, now))

        val inserted = entry("insert", event = "inserted")
        assertEquals(listOf(first, inserted, next), HistoryJournal.append(listOf(first, inserted), next, now))
    }

    @Test fun nonRawDictationSnapshotsNeverCoalesce() {
        EditMode.entries.filter { it != EditMode.RAW }.forEach { mode ->
            val first = entry(mode = mode)
            val next = entry("next", mode = mode)
            assertEquals(listOf(first, next), HistoryJournal.append(listOf(first), next, now))
            assertEquals(listOf(first, next.copy(mode = EditMode.RAW)),
                HistoryJournal.append(listOf(first), next.copy(mode = EditMode.RAW), now))
            assertEquals(listOf(first.copy(mode = EditMode.RAW), next),
                HistoryJournal.append(listOf(first.copy(mode = EditMode.RAW)), next, now))
        }
    }

    @Test fun retentionExpiresOnlyEntriesOlderThanThirtyDays() {
        val cutoff = now - HistoryJournal.MAX_AGE_MILLIS
        val expired = entry("expired", timestamp = cutoff - 1)
        val boundary = entry("boundary", timestamp = cutoff)
        val current = entry("current")

        assertEquals(listOf(boundary, current), HistoryJournal.retain(listOf(expired, boundary, current), now))
    }

    @Test fun retentionKeepsTheLastHundredEntries() {
        val entries = (0..HistoryJournal.MAX_ENTRIES + 5).map { entry("entry-$it", event = "edited") }
        assertEquals(entries.takeLast(HistoryJournal.MAX_ENTRIES), HistoryJournal.retain(entries, now))
    }

    @Test fun retentionBoundsUtf8BytesAndKeepsTheNewestPairsIntact() {
        val entries = (0..19).map { index ->
            entry("entry-$index", event = "edited", mode = EditMode.RAW_PLUS,
                original = "声".repeat(Draft.MAX_TRANSCRIPT), text = "界".repeat(HistoryJournal.MAX_TEXT))
        }
        val retained = HistoryJournal.retain(entries, now)
        assertTrue(retained.isNotEmpty() && retained.size < entries.size)
        assertEquals(entries.takeLast(retained.size), retained)
        assertTrue(HistoryJournal.encode(retained).toByteArray(Charsets.UTF_8).size <= HistoryJournal.MAX_BYTES)
        val justTooLarge = entries.takeLast(retained.size + 1)
        assertTrue(HistoryJournal.encode(justTooLarge).toByteArray(Charsets.UTF_8).size > HistoryJournal.MAX_BYTES)
        assertEquals(retained, HistoryJournal.decode(HistoryJournal.encode(retained)))
    }

    @Test fun decodeRejectsCorruptionUnknownModesAndWrongFieldTypes() {
        fun document(): JSONObject = JSONObject(HistoryJournal.encode(listOf(entry())))
        val invalid = listOf(
            "{",
            document().put("schemaVersion", "1").toString(),
            document().put("schemaVersion", 2).toString(),
            document().apply { getJSONArray("entries").getJSONObject(0).put("timestamp", "123") }.toString(),
            document().apply { getJSONArray("entries").getJSONObject(0).put("timestamp", 1.5) }.toString(),
            document().apply { getJSONArray("entries").getJSONObject(0).put("mode", "unknown") }.toString(),
            document().apply { getJSONArray("entries").getJSONObject(0).put("event", "host-app") }.toString(),
            document().apply { getJSONArray("entries").getJSONObject(0).put("original", 123) }.toString(),
            document().apply { getJSONArray("entries").getJSONObject(0).put("text", JSONObject.NULL) }.toString(),
            document().apply { getJSONArray("entries").getJSONObject(0).remove("sessionId") }.toString()
        )
        invalid.forEach { json -> assertThrows(Exception::class.java) { HistoryJournal.decode(json) } }
    }

    @Test fun invalidOrOversizedDocumentsAndEntriesAreRejected() {
        val invalidEntries = listOf(
            entry(original = " "),
            entry(original = "x".repeat(Draft.MAX_TRANSCRIPT + 1)),
            entry(text = "x".repeat(HistoryJournal.MAX_TEXT + 1)),
            entry(timestamp = 0),
            entry(session = "")
        )
        invalidEntries.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { HistoryJournal.append(emptyList(), invalid, now) }
        }
        val tooMany = JSONObject().put("schemaVersion", 1).put("entries", JSONArray().apply {
            repeat(HistoryJournal.MAX_ENTRIES + 1) {
                put(JSONObject(HistoryJournal.encode(listOf(entry()))).getJSONArray("entries").getJSONObject(0))
            }
        }).toString()
        assertThrows(IllegalArgumentException::class.java) { HistoryJournal.decode(tooMany) }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryJournal.decode(" ".repeat(HistoryJournal.MAX_BYTES + 1))
        }
    }
}
