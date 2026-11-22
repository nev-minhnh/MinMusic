package com.metrolist.shared.repository

import com.metrolist.shared.api.lyrics.LyricsCandidateInfo
import com.metrolist.shared.api.lyrics.LyricsMatch
import com.metrolist.shared.api.lyrics.LyricsMatchConfidence
import com.metrolist.shared.api.lyrics.LyricsMatchVerdict
import com.metrolist.shared.api.lyrics.LyricsQuery
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.lyricsTimingKind
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsWithProvider

/** How a provider ties its lyrics to the playing track. */
internal enum class LyricsIdentity {
    /** Looked up by the exact YouTube video id. */
    VIDEO_ID,

    /** The video's own captions: the right recording, but often speech recognition rather than the lyrics. */
    VIDEO_CAPTIONS,

    /** Found by title search; the provider reports the title, artist and length it matched. */
    CANDIDATE_METADATA,

    /** Matched by the provider's own server; only the lyrics come back. */
    SERVER_MATCHED,
}

/** Stands for a Zing MP3 / NhacCuaTui song's own lyrics in the automatic lookup (never a configured provider). */
internal val OWN_SOURCE_LYRICS = LyricsProvider.AUTO

internal fun lyricsIdentity(provider: LyricsProvider): LyricsIdentity =
    when (provider) {
        LyricsProvider.YOUTUBE, OWN_SOURCE_LYRICS -> LyricsIdentity.VIDEO_ID
        LyricsProvider.YOUTUBESUBTITLE -> LyricsIdentity.VIDEO_CAPTIONS
        LyricsProvider.SIMPMUSIC, LyricsProvider.LRCLIB, LyricsProvider.KUGOU, LyricsProvider.LRCRED, LyricsProvider.PAXSENIX ->
            LyricsIdentity.CANDIDATE_METADATA
        else -> LyricsIdentity.SERVER_MATCHED
    }

/** A provider's lyrics after the match check: possibly reduced to plain text when its timing cannot fit. */
internal data class AssessedLyrics(
    val provider: LyricsProvider,
    val providerIndex: Int,
    val result: LyricsWithProvider,
    val confidence: LyricsMatchConfidence,
    val timing: LyricsTimingKind,
    val words: Set<String>,
    val verdict: LyricsMatchVerdict? = null,
)

/**
 * Checks one candidate against the playing track. Returns null for lyrics of another song (or unusable ones).
 * Synced lyrics whose timing cannot belong to this recording come back as plain text.
 */
internal fun assessLyricsCandidate(
    provider: LyricsProvider,
    providerIndex: Int,
    candidate: LyricsWithProvider,
    query: LyricsQuery,
    videoId: String? = null,
): AssessedLyrics? {
    val document = LyricsUtils.parseMlxDocument(candidate.lyrics) ?: return null
    val lines = document.lines
    if (!usableProviderLyrics(lines)) return null
    val identity = lyricsIdentity(provider)
    val verdict =
        if (identity == LyricsIdentity.CANDIDATE_METADATA) {
            LyricsMatch.evaluate(query, LyricsCandidateInfo(document.song.title, document.song.artist, document.song.durationMs))
        } else {
            null
        }
    val confidence =
        when {
            verdict != null -> verdict.confidence
            identity == LyricsIdentity.VIDEO_ID -> LyricsMatchConfidence.CONFIDENT
            // Lyrics submitted for exactly this video.
            provider == LyricsProvider.UNISON && videoId != null && document.meta.sourceId == videoId -> LyricsMatchConfidence.CONFIDENT
            else -> LyricsMatchConfidence.PLAUSIBLE
        }.let {
            // SimpMusic entries are crowd-submitted per video (captions, translations): they need a witness.
            if (provider == LyricsProvider.SIMPMUSIC && it == LyricsMatchConfidence.CONFIDENT) LyricsMatchConfidence.PLAUSIBLE else it
        }
    if (confidence == LyricsMatchConfidence.REJECTED || !LyricsMatch.textFits(query.title, lines)) return null
    // Automatic captions ("[âm nhạc]", "[Music]") are speech recognition, not the lyrics.
    if (identity == LyricsIdentity.VIDEO_CAPTIONS && lines.any { SOUND_TAG.matches(it.text.trim()) }) return null
    var timing = lyricsTimingKind(lines)
    var result = candidate
    if (timing != LyricsTimingKind.UNSYNCED && (verdict?.allowSynced == false || !LyricsMatch.timingFits(lines, query.durationMs))) {
        result = candidate.copy(lyrics = LyricsMatch.toUnsyncedMlx(document, candidate.provider) ?: return null)
        timing = LyricsTimingKind.UNSYNCED
    }
    val words = lines.flatMapTo(mutableSetOf()) { LyricsMatch.normalize(it.text).split(' ') }.apply { remove("") }
    return AssessedLyrics(provider, providerIndex, result, confidence, timing, words, verdict)
}

/**
 * Lyrics matched only by a provider's server (or weakly) count as the right song once a confidently matched
 * candidate from another provider has the same words.
 */
internal fun corroborateLyrics(candidates: List<AssessedLyrics>): List<AssessedLyrics> =
    candidates.map { candidate ->
        if (candidate.confidence == LyricsMatchConfidence.PLAUSIBLE &&
            candidates.any { other ->
                other.provider != candidate.provider && other.confidence == LyricsMatchConfidence.CONFIDENT &&
                    wordAgreement(candidate.words, other.words) >= CORROBORATION_AGREEMENT
            }
        ) {
            candidate.copy(confidence = LyricsMatchConfidence.CONFIDENT)
        } else {
            candidate
        }
    }

/** The lyrics to show: only confident matches; then word-synced over line-synced over plain; then provider order. */
internal fun bestLyrics(candidates: List<AssessedLyrics>): AssessedLyrics? =
    corroborateLyrics(candidates)
        .filter { it.confidence == LyricsMatchConfidence.CONFIDENT }
        .maxWithOrNull(compareBy<AssessedLyrics> { it.timing }.thenByDescending { it.providerIndex })

internal fun wordAgreement(
    a: Set<String>,
    b: Set<String>,
): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return a.intersect(b).size.toDouble() / a.union(b).size
}

internal const val CORROBORATION_AGREEMENT = 0.5

private val SOUND_TAG = Regex("""\[[^\]]{1,24}]""")
