package com.metrolist.core.api.tracksource

import com.metrolist.core.api.Quality
import com.metrolist.core.api.sources.SourcePrefs
import com.metrolist.core.api.sources.SourceAccountCache
import com.metrolist.core.api.sources.SourcesMapper
import com.metrolist.core.api.sources.TrackOverride
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.desktop.state.playback.PlayerState as DesktopPlayerState
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.source.BestSourceSelector
import com.metrolist.shared.source.QualityRanker
import com.metrolist.shared.source.SourceEntitlement
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceQuality
import com.metrolist.shared.source.SourceRegistry
import com.metrolist.shared.source.sourceOf
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import org.koin.mp.KoinPlatform
import java.util.concurrent.ConcurrentHashMap

/**
 * api.md 3.4 `track.sources` / `track.setSource`: the same recording on the other sources, and a per-track choice.
 * Nothing here unlocks content: a source that wants a VIP login answers `available=false, hint="vip_login"` and cannot be chosen.
 */
object TrackSourceApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "track.sources" to handler { params, _ -> sources(params.requireStr("id")) },
            "track.setSource" to handler { params, _ -> setSource(params.requireStr("id"), params.requireStr("source")) },
        )

    private const val STREAM_TIMEOUT_MS = 4_000L
    private const val LOOKUP_TIMEOUT_MS = 5_000L
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private class Match(
        val song: SongItem,
        val quality: Quality,
        val available: Boolean,
        val hint: String?,
        val at: Long,
    )

    // "<trackId>|<source key>" -> the match found for that source (10 minutes).
    private val cache = ConcurrentHashMap<String, Match>()

    // trackId -> source key -> matched song, from the last track.sources answer; only choosable (available) options.
    private val offered = ConcurrentHashMap<String, Map<String, SongItem>>()

    private val registry: SourceRegistry? get() = KoinPlatform.getKoinOrNull()?.getOrNull<SourceRegistry>()

    // --- track.sources -------------------------------------------------------------------------------------------

    private suspend fun sources(id: String): JsonElement {
        backendReady()
        val song = resolveSong(id)
        val current = sourceOf(id)
        val options = mutableListOf<JsonObject>()
        options += TrackSourceLabels.option(current, id, SourcesMapper.maxQuality(current, isVip(current)), auto = true, available = true)
        val choosable = mutableMapOf<String, SongItem>()
        val sources = registry
        if (sources != null) {
            val others = SourcesMapper.IDS.filter { it != current && sources.available(it) != null }
            val matches =
                supervisorScope {
                    others.map { other -> async { other to matchFor(sources, other, id, song) } }.awaitAll()
                }
            for ((other, match) in matches) {
                if (match == null) continue
                options += TrackSourceLabels.option(other, match.song.id, match.quality, auto = false, available = match.available, hint = match.hint)
                if (match.available) choosable[other.key] = match.song
            }
        }
        offered[id] = choosable
        return buildJsonObject { put("options", JsonArray(options)) }
    }

    /** The same recording on [source], with its stream quality, or null when it is not there (or the source failed). */
    private suspend fun matchFor(
        registry: SourceRegistry,
        source: SourceId,
        trackId: String,
        song: SongItem,
    ): Match? {
        val key = "$trackId|${source.key}"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return it }
        val max = SourcesMapper.maxQuality(source, isVip(source))
        val query = "${song.title} ${song.artists.firstOrNull()?.name.orEmpty()}".trim()
        val found =
            try {
                // MusicSource.search(query, filter, page): SourcePage<YTItem>; BestSourceSelector.isSameRecording(song, candidate): Boolean
                registry.call(source) { it.search(query, SearchFilter.SONG, 0) }.items.filterIsInstance<SongItem>().firstOrNull {
                    BestSourceSelector.isSameRecording(song, it)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return null
        val match =
            try {
                // MusicSource.stream(id, quality): PlaybackResource (mimeType, codecs, bitrate in bits per second)
                val resource = withTimeoutOrNull(STREAM_TIMEOUT_MS) { registry.call(source) { it.stream(found.id, SourceQuality.HIGH) } }
                if (resource == null) {
                    Match(found, max, true, null, now)
                } else {
                    val label = TrackSourceLabels.quality(QualityRanker.tier(resource), resource.bitrate?.div(1000))
                    Match(found, label ?: max, true, null, now)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val reason = TrackSourceLabels.reasonOf(error)
                val hint = reason?.let(TrackSourceLabels::hint)
                // A refusal with a hint (VIP, region, preview) is shown but cannot be chosen; any other failure hides the source.
                if (hint == null) null else Match(found, max, false, hint, now)
            }
        if (match != null) cache[key] = match
        return match
    }

    private fun isVip(source: SourceId): Boolean = SourceAccountCache.get(source)?.account?.entitlement == SourceEntitlement.VIP

    // --- track.setSource -----------------------------------------------------------------------------------------

    private suspend fun setSource(
        id: String,
        sourceKey: String,
    ): JsonElement {
        backendReady()
        val chosen = SourcesMapper.parseId(sourceKey) ?: badRequest("source: unknown source")
        val previous = SourcePrefs.trackOverrides[id]
        val replacement: SongItem
        if (chosen == sourceOf(id)) {
            // Back to the original source.
            SourcePrefs.setOverride(id, null)
            replacement = resolveSong(id)
        } else {
            if (offered[id] == null) sources(id)
            val match = offered[id]?.get(chosen.key) ?: throw ApiException("bad_request", "this source is not available for the track")
            SourcePrefs.setOverride(id, TrackOverride(chosen.key, match.id))
            TrackSourceOverrides.remember(match)
            replacement = match
        }
        replaceInQueue(setOfNotNull(id, previous?.id), replacement)
        return emptyResult()
    }

    /** Swaps every queue entry of the track for [replacement] through the public `MusicQueue.updateItems`. */
    private suspend fun replaceInQueue(
        ids: Set<String>,
        replacement: SongItem,
    ) {
        withContext(Dispatchers.Main) {
            val queue = DesktopPlayerState.queue
            // MusicQueue.updateItems(updates: Map<Int, SongItem>) republishes the queue entries in place.
            val updates = queue.state.value.entries.withIndex().filter { it.value.song.id in ids }.associate { it.index to replacement }
            if (updates.isNotEmpty()) queue.updateItems(updates)
        }
    }

    // --- minimal resolver (PlayerApi.resolve is not touched by this package) -------------------------------------------

    private suspend fun resolveSong(id: String): SongItem =
        withContext(Dispatchers.IO) {
            val stored = GlobalContext.get().get<DatabaseDao>().getSongsByIds(listOf(id)).firstOrNull()?.toSongItem()
            stored ?: remoteSong(id) ?: throw ApiException("not_found", "track not found")
        }

    private suspend fun remoteSong(id: String): SongItem? {
        val source = sourceOf(id)
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            if (source == SourceId.YT) {
                runCatching { GlobalYouTubeRepository.instance.getSongMetadata(id) }.getOrNull()
            } else {
                runCatching { registry?.call(source) { it.track(id) } }.getOrNull()
            }
        }
    }
}
