package com.metrolist.shared.source

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.Clock

/**
 * Plays or downloads a YouTube song from another source when the user's own VIP account there offers a higher
 * [QualityRanker.Tier] of the same recording: same title and artist, duration within [DURATION_TOLERANCE_S].
 * Matches are cached per song id and searched in the background, so playback waits at most [budgetMs] for the
 * alternative; on timeout or any failure the YouTube stream is used.
 */
class BestSourceSelector(
    private val registry: SourceRegistry,
    private val enabled: () -> Boolean = { Settings.shared.autoBestSource },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val candidates: List<SourceId> = listOf(SourceId.ZING, SourceId.NCT),
    private val budgetMs: Long = BUDGET_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private class Cached<T>(
        val value: T,
        val expiresAtMs: Long,
    )

    private val lock = ReentrantLock()
    private val matches = mutableMapOf<String, Cached<List<String>>>()
    private val pending = mutableMapOf<String, Deferred<List<String>>>()
    private val vip = mutableMapOf<SourceId, Cached<Boolean>>()
    private val rejected = mutableMapOf<String, Long>()

    fun appliesTo(
        videoId: String,
        song: SongItem?,
        hints: ContentHints,
    ): Boolean {
        if (song == null || song.id != videoId || !enabled() || hints.wantVideo) return false
        if (!song.isYouTube || song.isUploaded || song.podcastId != null || song.isVideoSong) return false
        if ((song.duration ?: 0L) <= 0L) return false
        if (lock.withLock { rejected[videoId]?.let { it > nowMs() } } == true) return false
        return candidates.any { registry.available(it) != null }
    }

    /** Resolves [youTube] and the alternative in parallel and returns whichever ranks higher. */
    suspend fun resolve(
        videoId: String,
        song: SongItem,
        quality: SourceQuality,
        youTube: suspend () -> PlaybackResource,
    ): PlaybackResource =
        coroutineScope {
            val alternative = async { withTimeoutOrNull(budgetMs) { bestAlternative(videoId, song, quality) } }
            val primary =
                try {
                    youTube()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return@coroutineScope alternative.await()?.copy(videoId = videoId) ?: throw error
                }
            if (primary.networkUrl == null) {
                // A downloaded or cached copy always wins.
                alternative.cancel()
                return@coroutineScope primary
            }
            val other = alternative.await()
            if (other != null && QualityRanker.tier(other) > QualityRanker.tier(primary)) other.copy(videoId = videoId) else primary
        }

    /**
     * Called for every playback failure of a YouTube id. When the failed stream came from a substituted source, the
     * song plays from YouTube for [REJECT_MS]; returns true in that case.
     */
    fun onPlaybackFailure(
        videoId: String,
        clientName: String?,
    ): Boolean {
        if (candidates.none { it.key == clientName }) return false
        lock.withLock { rejected[videoId] = nowMs() + REJECT_MS }
        return true
    }

    /** Forgets cached entitlements, e.g. after the user logs in or out of a source. */
    fun invalidateAccounts() {
        lock.withLock {
            vip.clear()
            matches.entries.removeAll { it.value.value.isEmpty() }
        }
    }

    private suspend fun bestAlternative(
        videoId: String,
        song: SongItem,
        quality: SourceQuality,
    ): PlaybackResource? {
        var best: PlaybackResource? = null
        for (id in matchesFor(videoId, song)) {
            val resource =
                try {
                    registry.call(sourceOf(id)) { it.stream(id, quality) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    continue
                }
            val current = best
            if (current == null || QualityRanker.score(resource) > QualityRanker.score(current)) best = resource
        }
        return best
    }

    private suspend fun matchesFor(
        videoId: String,
        song: SongItem,
    ): List<String> {
        val cached = lock.withLock { matches[videoId]?.takeIf { it.expiresAtMs > nowMs() } }
        if (cached != null) return cached.value
        val job =
            lock.withLock {
                pending.getOrPut(videoId) {
                    scope.async {
                        val found =
                            try {
                                findMatches(song)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                        lock.withLock {
                            pending.remove(videoId)
                            if (found != null) {
                                if (matches.size >= MAX_CACHED_MATCHES) matches.clear()
                                matches[videoId] = Cached(found, nowMs() + if (found.isEmpty()) MISS_TTL_MS else MATCH_TTL_MS)
                            }
                        }
                        found.orEmpty()
                    }
                }
            }
        // Awaiting does not cancel the shared search, so a timed-out lookup still fills the cache for next time.
        return job.await()
    }

    /** Ids of matching songs on VIP sources, or null when no source could be searched. */
    private suspend fun findMatches(song: SongItem): List<String>? {
        var searched = false
        val found = mutableListOf<String>()
        val query = listOfNotNull(song.title, song.artists.firstOrNull()?.name).joinToString(" ")
        for (id in candidates) {
            val source = registry.available(id) ?: continue
            if (!isVip(source)) continue
            searched = true
            val items =
                try {
                    source.search(query, SearchFilter.SONG, 0).items
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    continue
                }
            items.filterIsInstance<SongItem>().firstOrNull { isSameRecording(song, it) }?.let { found += it.id }
        }
        return found.takeIf { searched }
    }

    private suspend fun isVip(source: MusicSource): Boolean {
        val cached = lock.withLock { vip[source.id]?.takeIf { it.expiresAtMs > nowMs() } }
        if (cached != null) return cached.value
        val value =
            try {
                source.account()?.entitlement == SourceEntitlement.VIP
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        lock.withLock { vip[source.id] = Cached(value, nowMs() + ACCOUNT_TTL_MS) }
        return value
    }

    companion object {
        const val BUDGET_MS = 1_500L
        const val DURATION_TOLERANCE_S = 3L
        private const val MATCH_TTL_MS = 6 * 60 * 60 * 1000L
        private const val MISS_TTL_MS = 60 * 60 * 1000L
        private const val ACCOUNT_TTL_MS = 30 * 60 * 1000L
        private const val REJECT_MS = 30 * 60 * 1000L
        private const val MAX_CACHED_MATCHES = 2_000

        private val featuring = Regex("""[(\[]\s*(?:feat|ft)\.?\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val artistSeparator = Regex("""\s*[,&]\s*|\s+(?:feat|ft)\.?\s+""", RegexOption.IGNORE_CASE)

        fun isSameRecording(
            song: SongItem,
            candidate: SongItem,
        ): Boolean {
            if (candidate.isUnavailable) return false
            val duration = song.duration ?: return false
            val candidateDuration = candidate.duration ?: return false
            if (abs(duration - candidateDuration) > DURATION_TOLERANCE_S) return false
            if (normalize(song.title).let { it.isEmpty() || it != normalize(candidate.title) }) return false
            val artists = artistNames(song.artists.map { it.name })
            return artistNames(candidate.artists.map { it.name }).any { it in artists }
        }

        fun normalize(text: String): String =
            text
                .replace(featuring, " ")
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter(String::isNotEmpty)
                .joinToString(" ")

        private fun artistNames(names: List<String>): Set<String> =
            names
                .flatMap { it.split(artistSeparator) }
                .map(::normalize)
                .filter(String::isNotEmpty)
                .toSet()
    }
}
