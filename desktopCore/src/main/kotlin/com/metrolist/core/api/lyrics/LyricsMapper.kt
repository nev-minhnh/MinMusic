package com.metrolist.core.api.lyrics

import com.metrolist.core.api.jsonOf
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.WordTimestamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

/**
 * Pure mapping of the v1 lyrics (`LyricsEntry` list) to the api.md 3.7 `Lyrics` shape. Times: `LyricsEntry.time` and
 * `endTime` are ms already; `WordTimestamp.startTime` / `endTime` are seconds and become ms here.
 */
object LyricsMapper {
    /** `word` when any line carries real word timing, else `line` when the lines are timed, else `none`. */
    fun syncedOf(
        entries: List<LyricsEntry>,
        hasPlaybackTiming: (List<LyricsEntry>) -> Boolean = LyricsUtils::hasPlaybackTiming,
    ): String =
        when {
            entries.any { hasWordTiming(it) } -> "word"
            hasPlaybackTiming(entries) -> "line"
            else -> "none"
        }

    /** True when the entry has word timing of its own (not just the line timing repeated as one word). */
    fun hasWordTiming(entry: LyricsEntry): Boolean {
        val words = entry.words // a property of another module: no smart cast on it directly
        return words != null && words.none { it.isLineTimed }
    }

    /** `synced` for a provider result's timing kind. */
    fun syncedOf(kind: LyricsTimingKind): String =
        when (kind) {
            LyricsTimingKind.WORD_SYNCED -> "word"
            LyricsTimingKind.LINE_SYNCED -> "line"
            LyricsTimingKind.UNSYNCED -> "none"
        }

    /**
     * Agent label per agent id, in order of first appearance among the non-background lines: the first id is `v1`,
     * the second `v2`; further ids have no label. Background lines are labelled `bg` by [line].
     */
    fun agentLabels(entries: List<LyricsEntry>): Map<String, String> {
        val labels = LinkedHashMap<String, String>()
        for (entry in entries) {
            val id = entry.agent?.takeIf { it.isNotBlank() } ?: continue
            if (entry.isBackground || id in labels || labels.size >= 2) continue
            labels[id] = if (labels.isEmpty()) "v1" else "v2"
        }
        return labels
    }

    private fun ms(seconds: Double): Long = (seconds * 1000.0).roundToLong()

    /** Word text as the UI concatenates it: the word plus its trailing space. */
    private fun wordText(word: WordTimestamp): String = if (word.hasTrailingSpace) word.text + " " else word.text

    /**
     * The words of a line for a `word` synced track: real timed words clamped into the line (start not before the
     * line, end not after `end`, end not before start); a line without word timing becomes one word spanning it.
     */
    private fun words(
        entry: LyricsEntry,
        endMs: Long?,
    ): JsonArray {
        val lineEnd = endMs ?: entry.time
        val source = entry.words?.takeIf { hasWordTiming(entry) && it.isNotEmpty() }
        val items =
            if (source == null) {
                listOf(Triple(entry.time, lineEnd, entry.text))
            } else {
                source.map { w ->
                    val start = ms(w.startTime).coerceAtLeast(entry.time)
                    val end = ms(w.endTime).let { if (endMs != null) it.coerceAtMost(endMs) else it }.coerceAtLeast(start)
                    Triple(start, end, wordText(w))
                }
            }
        return JsonArray(
            items.map { (start, end, text) ->
                jsonOf("t" to JsonPrimitive(start), "end" to JsonPrimitive(end), "text" to JsonPrimitive(text))
            },
        )
    }

    fun line(
        entry: LyricsEntry,
        labels: Map<String, String>,
        wordSynced: Boolean,
        timed: Boolean,
    ): JsonObject {
        val end = if (timed) entry.endTime?.takeIf { it >= entry.time } else null
        val agent =
            when {
                entry.isBackground -> "bg"
                else -> entry.agent?.let { labels[it] }
            }
        return jsonOf(
            "t" to JsonPrimitive(entry.time),
            "end" to end?.let { JsonPrimitive(it) },
            "text" to JsonPrimitive(entry.text),
            "words" to if (wordSynced) words(entry, end) else null,
            "tr" to entry.translatedText?.let { JsonPrimitive(it) },
            "ro" to entry.romanizedText?.let { JsonPrimitive(it) },
            "agent" to agent?.let { JsonPrimitive(it) },
        )
    }

    /** The api.md `Lyrics` object. Lines are ordered by time (stable, so a background line keeps its place after its lead line). */
    fun lyrics(
        trackId: String,
        provider: String?,
        offsetMs: Int,
        entries: List<LyricsEntry>,
        hasPlaybackTiming: (List<LyricsEntry>) -> Boolean = LyricsUtils::hasPlaybackTiming,
    ): JsonObject {
        val sorted = entries.sortedBy { it.time }
        val synced = syncedOf(sorted, hasPlaybackTiming)
        val labels = agentLabels(sorted)
        return jsonOf(
            "trackId" to JsonPrimitive(trackId),
            "provider" to JsonPrimitive(provider.orEmpty()),
            "synced" to JsonPrimitive(synced),
            "offsetMs" to JsonPrimitive(offsetMs),
            "lines" to JsonArray(sorted.map { line(it, labels, wordSynced = synced == "word", timed = synced != "none") }),
        )
    }
}
