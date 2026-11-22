/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class KuGouLyricsProvider(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val DURATION_TOLERANCE = 8
        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): String? {
        val keyword = generateKeyword(title, artist, album)
        val query = LyricsQuery(title, artist, duration.takeIf { it > 0 }?.let { it * 1_000L })
        val match =
            searchSongs(keyword, query)
                ?: keyword.album
                    ?.takeIf { it.isNotBlank() }
                    ?.let { searchSongs(keyword.copy(album = null), query) }
                ?: return null
        val lrc = downloadLyrics(match.id, match.accessKey) ?: return null
        return lrcToMlx(lrc.normalize(), title, artist)?.let { LyricsMatch.withMatchedSong(it, match.song, match.singer, match.durationMs) }
    }

    private class Candidate(
        val id: Long,
        val accessKey: String,
        val durationMs: Long,
        val song: String?,
        val singer: String?,
    )

    private suspend fun searchSongs(
        keyword: Keyword,
        track: LyricsQuery,
    ): Candidate? {
        val duration = track.durationMs?.let { (it / 1_000L).toInt() } ?: 0
        val query =
            buildString {
                // KuGou only matches "artist - title"; "title - artist" returns no candidates.
                if (keyword.artist.isNotBlank()) {
                    append(keyword.artist)
                    append(" - ")
                }
                append(keyword.title)
                if (!keyword.album.isNullOrBlank()) {
                    append(' ')
                    append(keyword.album)
                }
            }.trim()
        if (query.isEmpty()) return null
        val response: String =
            httpClient
                .prepareGet("https://lyrics.kugou.com/search") {
                    expectMissingLyrics()
                    parameter("ver", 1)
                    parameter("man", "yes")
                    parameter("client", "pc")
                    parameter("keyword", query)
                    if (duration > 0) parameter("duration", duration * 1_000L)
                }.readLyricsText() ?: return null
        val root = json.parseToJsonElement(response).jsonObject
        val candidates = checkNotNull(root["candidates"]?.jsonArray) { "Missing lyrics candidates" }
        val parsedCandidates =
            candidates.mapNotNull { el ->
                val obj = el.jsonObject
                val rawDuration = obj["duration"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                Candidate(
                    id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    accessKey = obj["accesskey"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    // KuGou reports milliseconds; older answers used seconds.
                    durationMs = if (rawDuration in 1 until 10_000) rawDuration * 1_000L else rawDuration,
                    song = obj["song"]?.jsonPrimitive?.contentOrNull,
                    singer = obj["singer"]?.jsonPrimitive?.contentOrNull,
                )
            }
        // KuGou also lists other songs (same artist, similar words): keep only this title by this artist,
        // then the closest length.
        return parsedCandidates
            .map { it to LyricsMatch.evaluate(track, LyricsCandidateInfo(it.song, it.singer, it.durationMs)) }
            .filter { (candidate, verdict) ->
                verdict.confidence != LyricsMatchConfidence.REJECTED &&
                    (duration <= 0 || durationDiffSeconds(candidate.durationMs, duration) <= DURATION_TOLERANCE)
            }.sortedWith(
                compareByDescending<Pair<Candidate, LyricsMatchVerdict>> { it.second.confidence }
                    .thenBy { it.second.durationDeltaMs ?: Long.MAX_VALUE },
            ).firstOrNull()
            ?.first
    }

    // KuGou reports milliseconds; fixtures use seconds.
    private fun durationDiffSeconds(
        candidateDuration: Long,
        targetSeconds: Int,
    ): Double {
        val secDiff = kotlin.math.abs(candidateDuration - targetSeconds).toDouble()
        val msDiff = kotlin.math.abs(candidateDuration - targetSeconds * 1_000L) / 1_000.0
        return minOf(secDiff, msDiff)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadLyrics(
        id: Long,
        accessKey: String,
    ): String? {
        val response: String =
            httpClient
                .prepareGet("https://lyrics.kugou.com/download") {
                    expectMissingLyrics()
                    parameter("fmt", "lrc")
                    parameter("charset", "utf8")
                    parameter("client", "pc")
                    parameter("ver", 1)
                    parameter("id", id)
                    parameter("accesskey", accessKey)
                }.readLyricsText() ?: return null
        val root = json.parseToJsonElement(response).jsonObject
        val content = checkNotNull(root["content"]?.jsonPrimitive?.content) { "Missing lyrics content" }
        return Base64.decode(content).decodeToString()
    }

    private fun generateKeyword(
        title: String,
        artist: String,
        album: String?,
    ) = Keyword(normalizeTitle(title), normalizeArtist(artist), album)

    private fun normalizeTitle(title: String) =
        title
            .replace(Regex("\\(.*\\)"), "")
            .replace(Regex("（.*）"), "")
            .replace(Regex("「.*」"), "")
            .replace(Regex("『.*』"), "")
            .replace(Regex("<.*>"), "")
            .replace(Regex("《.*》"), "")
            .replace(Regex("〈.*〉"), "")
            .replace(Regex("＜.*＞"), "")
            .trim()

    private fun normalizeArtist(artist: String) =
        artist
            .replace(", ", "、")
            .replace(" & ", "、")
            .replace(".", "")
            .replace("和", "、")
            .replace(Regex("\\(.*\\)"), "")
            .replace(Regex("（.*）"), "")
            .trim()

    private fun String.normalize(): String {
        val credits =
            Regex(
                "^(?:作词|作曲|词|曲|编曲|制作人|词曲|填词|混音|录音|监制|Lyrics|Composer|Composed by|Written by|Arranger|Producer)\\s*[:：]",
                RegexOption.IGNORE_CASE,
            )
        return lineSequence()
            .filter { it.contains(lrcTimestampRegex) }
            .filterNot { credits.containsMatchIn(it.replace(lrcTimestampRegex, "").trim()) }
            .joinToString("\n")
    }
}

data class Keyword(
    val title: String,
    val artist: String,
    val album: String? = null,
)

private val lrcTimestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]""")

internal fun lrcToMlx(
    lrc: String,
    title: String?,
    artist: String?,
    source: String = "LRC",
    sourceId: String? = null,
): String? {
    val builder = MlxBuilder().meta(source, sourceId = sourceId).song(title = title, artist = artist)
    val sourceOffsetMs = lrcOffsetMs(lrc)
    if (sourceOffsetMs != 0L) builder.unsyncedLine("[offset:$sourceOffsetMs]")
    val timed = mutableListOf<Pair<Long, String>>()
    val plain = mutableListOf<String>()
    for (rawLine in lrc.lines()) {
        val line = rawLine.trim()
        var offset = 0
        val times = mutableListOf<Long>()
        while (true) {
            val match = lrcTimestampRegex.matchAt(line, offset) ?: break
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val millis = match.groupValues[3].padEnd(3, '0').toLong()
            times += minutes * 60_000L + seconds * 1_000L + millis
            offset = match.range.last + 1
        }
        val text = line.substring(offset).trim()
        if (text.isBlank()) continue
        if (times.isNotEmpty()) {
            times.forEach { timed += it to text }
        } else if (!line.startsWith("[")) {
            plain += line
        }
    }
    timed.sortedBy { it.first }.forEach { (time, text) -> builder.line(time, text) }
    plain.forEach(builder::unsyncedLine)
    return if (timed.isEmpty() && plain.isEmpty()) null else builder.build()
}
