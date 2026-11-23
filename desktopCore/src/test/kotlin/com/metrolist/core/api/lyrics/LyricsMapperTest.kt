package com.metrolist.core.api.lyrics

import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.WordTimestamp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsMapperTest {
    private val timed: (List<LyricsEntry>) -> Boolean = { lines -> lines.size > 1 && lines.any { it.time > 0 } }

    private fun word(
        text: String,
        start: Double,
        end: Double,
        trailing: Boolean = true,
        lineTimed: Boolean = false,
    ) = WordTimestamp(text = text, startTime = start, endTime = end, hasTrailingSpace = trailing, isLineTimed = lineTimed)

    private val wordLines =
        listOf(
            LyricsEntry(
                time = 1000,
                text = "xin chao",
                endTime = 3000,
                agent = "a1",
                words = listOf(word("xin", 1.0, 1.8), word("chao", 1.8, 3.0, trailing = false)),
            ),
            LyricsEntry(time = 4000, text = "tam biet", endTime = 6000, agent = "a2", words = listOf(word("tam", 4.0, 5.0), word("biet", 5.0, 6.0))),
        )

    private fun lyrics(
        entries: List<LyricsEntry>,
        provider: String? = "LrcLib",
        offset: Int = 0,
    ): JsonObject = LyricsMapper.lyrics("t1", provider, offset, entries, timed)

    @Test
    fun wordTimesAreSecondsInTheSourceAndMillisecondsOnTheWire() {
        val out = lyrics(wordLines)
        assertEquals("word", out["synced"]!!.jsonPrimitive.content)
        val words = out["lines"]!!.jsonArray[0].jsonObject["words"]!!.jsonArray
        assertEquals(1000L, words[0].jsonObject["t"]!!.jsonPrimitive.long)
        assertEquals(1800L, words[0].jsonObject["end"]!!.jsonPrimitive.long)
        assertEquals("xin ", words[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("chao", words[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(3000L, words[1].jsonObject["end"]!!.jsonPrimitive.long)
    }

    @Test
    fun wordsJoinToTheLineText() {
        val line = lyrics(wordLines)["lines"]!!.jsonArray[0].jsonObject
        val joined = line["words"]!!.jsonArray.joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals(line["text"]!!.jsonPrimitive.content, joined.trim())
    }

    @Test
    fun wordsAreClampedIntoTheirLine() {
        val entry = LyricsEntry(time = 1000, text = "a", endTime = 2000, words = listOf(word("a", 0.5, 2.7, trailing = false)))
        val second = LyricsEntry(time = 5000, text = "b", endTime = 6000, words = listOf(word("b", 5.0, 6.0)))
        val firstLine = lyrics(listOf(entry, second))["lines"]!!.jsonArray[0].jsonObject
        val w = firstLine["words"]!!.jsonArray[0].jsonObject
        assertEquals(1000L, w["t"]!!.jsonPrimitive.long)
        assertEquals(2000L, w["end"]!!.jsonPrimitive.long)
    }

    @Test
    fun lineSyncedTrackHasNoWords() {
        val entries = listOf(LyricsEntry(time = 1000, text = "a"), LyricsEntry(time = 4000, text = "b"))
        val out = lyrics(entries)
        assertEquals("line", out["synced"]!!.jsonPrimitive.content)
        assertTrue(out["lines"]!!.jsonArray.all { "words" !in it.jsonObject })
    }

    @Test
    fun untimedLyricsAreNoneWithoutEnds() {
        val entries = listOf(LyricsEntry(time = 0, text = "a", endTime = 5), LyricsEntry(time = 0, text = "b"))
        val out = lyrics(entries)
        assertEquals("none", out["synced"]!!.jsonPrimitive.content)
        assertTrue(out["lines"]!!.jsonArray.all { "end" !in it.jsonObject })
    }

    @Test
    fun lineTimedWordsDoNotMakeAWordTrack() {
        val entries =
            listOf(
                LyricsEntry(time = 1000, text = "a", words = listOf(word("a", 1.0, 2.0, lineTimed = true))),
                LyricsEntry(time = 4000, text = "b"),
            )
        assertEquals("line", LyricsMapper.syncedOf(entries, timed))
        assertFalse(LyricsMapper.hasWordTiming(entries[0]))
    }

    @Test
    fun aLineWithoutWordsInAWordTrackBecomesOneWord() {
        val entries = wordLines + LyricsEntry(time = 7000, text = "instrumental", endTime = 9000)
        val last = lyrics(entries)["lines"]!!.jsonArray[2].jsonObject["words"]!!.jsonArray
        assertEquals(1, last.size)
        assertEquals(7000L, last[0].jsonObject["t"]!!.jsonPrimitive.long)
        assertEquals(9000L, last[0].jsonObject["end"]!!.jsonPrimitive.long)
        assertEquals("instrumental", last[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun agentsAreV1ThenV2AndBackgroundIsBg() {
        val entries =
            listOf(
                LyricsEntry(time = 1000, text = "a", agent = "x"),
                LyricsEntry(time = 2000, text = "b", agent = "y"),
                LyricsEntry(time = 2500, text = "bg", agent = "x", isBackground = true),
                LyricsEntry(time = 3000, text = "c", agent = "x"),
                LyricsEntry(time = 4000, text = "d", agent = "z"),
                LyricsEntry(time = 5000, text = "e"),
            )
        val agents = lyrics(entries)["lines"]!!.jsonArray.map { it.jsonObject["agent"]?.jsonPrimitive?.content }
        assertEquals(listOf("v1", "v2", "bg", "v1", null, null), agents)
        assertEquals(mapOf("x" to "v1", "y" to "v2"), LyricsMapper.agentLabels(entries))
    }

    @Test
    fun aBackgroundLineDoesNotTakeV1() {
        val entries = listOf(LyricsEntry(time = 1000, text = "bg", agent = "b", isBackground = true), LyricsEntry(time = 2000, text = "a", agent = "x"))
        assertEquals(mapOf("x" to "v1"), LyricsMapper.agentLabels(entries))
    }

    @Test
    fun translationAndRomanizationAreCopied() {
        val entries =
            listOf(
                LyricsEntry(time = 1000, text = "a", translatedText = "A!", romanizedText = "a~"),
                LyricsEntry(time = 4000, text = "b"),
            )
        val lines = lyrics(entries)["lines"]!!.jsonArray
        assertEquals("A!", lines[0].jsonObject["tr"]!!.jsonPrimitive.content)
        assertEquals("a~", lines[0].jsonObject["ro"]!!.jsonPrimitive.content)
        assertNull(lines[1].jsonObject["tr"])
        assertNull(lines[1].jsonObject["ro"])
    }

    @Test
    fun headerFieldsAndOrdering() {
        val out = lyrics(listOf(LyricsEntry(time = 4000, text = "b"), LyricsEntry(time = 1000, text = "a")), provider = null, offset = -250)
        assertEquals("t1", out["trackId"]!!.jsonPrimitive.content)
        assertEquals("", out["provider"]!!.jsonPrimitive.content)
        assertEquals(-250L, out["offsetMs"]!!.jsonPrimitive.long)
        assertEquals(listOf("a", "b"), out["lines"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
    }

    @Test
    fun timingKindMapsToSynced() {
        assertEquals("word", LyricsMapper.syncedOf(LyricsTimingKind.WORD_SYNCED))
        assertEquals("line", LyricsMapper.syncedOf(LyricsTimingKind.LINE_SYNCED))
        assertEquals("none", LyricsMapper.syncedOf(LyricsTimingKind.UNSYNCED))
    }

    @Test
    fun waitDecisions() {
        val w = LyricsWait
        assertEquals(LyricsWait.Decision.WAIT, w.decide(false, 0, false, false, 10_000))
        assertEquals(LyricsWait.Decision.READY, w.decide(true, 5, false, true, 0))
        assertEquals(LyricsWait.Decision.WAIT, w.decide(true, 0, true, true, 0))
        assertEquals(LyricsWait.Decision.MISS, w.decide(true, 0, false, true, 100))
        assertEquals(LyricsWait.Decision.WAIT, w.decide(true, 0, false, false, 100))
        assertEquals(LyricsWait.Decision.MISS, w.decide(true, 0, false, false, LyricsWait.NO_LOAD_GRACE_MS))
    }
}
