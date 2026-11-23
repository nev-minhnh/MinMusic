package com.metrolist.core.api.lyrics

import androidx.compose.runtime.snapshotFlow
import com.metrolist.core.CoreLog
import com.metrolist.core.api.PlayerApi
import com.metrolist.core.api.jsonOf
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.int
import com.metrolist.core.server.notFound
import com.metrolist.core.server.requireBool
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.core.server.topic
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState as DesktopPlayerState
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.repository.LyricsProviderGroup
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext

/**
 * api.md 3.7 lyrics methods and the `lyrics` topic, bridged from the desktop [LyricsState]. All LyricsState calls run
 * on core-main. Search, choose, offset, translate, romanize and edit work on the playing track; `lyrics.get` also
 * answers for another track (read through the LyricsRepository, without touching LyricsState).
 */
object LyricsApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "lyrics.get" to handler { params, _ -> get(params.requireStr("trackId")) },
            "lyrics.search" to handler { params, _ -> search(params.requireStr("trackId"), params.str("query")) },
            "lyrics.choose" to handler { params, _ -> choose(params.requireStr("trackId"), params.requireStr("candidateId")) },
            "lyrics.setOffset" to handler { params, _ -> setOffset(params.requireStr("trackId"), params.int("ms") ?: badRequest("missing number param 'ms'")) },
            "lyrics.translate" to handler { params, _ -> translate(params.requireStr("trackId"), params.requireBool("on"), params.str("target")) },
            "lyrics.romanize" to handler { params, _ -> romanize(params.requireStr("trackId"), params.requireBool("on")) },
            "lyrics.edit" to handler { params, _ -> edit(params.requireStr("trackId"), params.requireStr("lrc")) },
            "lyrics.providers" to handler { _, _ -> providers() },
            "lyrics.providers.set" to handler { params, _ -> setProviders(params) },
        )

    private const val LYRICS_WAIT_MS = 15_000L
    private const val POLL_MS = 100L
    private const val SEARCH_WAIT_MS = 15_000L
    private const val OFFSET_LIMIT_MS = 30_000

    // --- the playing track ---------------------------------------------------------------------------------------

    /** The playing track when its id is [trackId]; anything else is `not_found` (these methods only work on the playing track). */
    private suspend fun currentSong(trackId: String): SongItem {
        backendReady()
        PlayerApi.ensureCoreDefaults()
        val song = withContext(Dispatchers.Main) { DesktopPlayerState.currentTrack }
        if (song == null || song.id != trackId) notFound("lyrics are only available for the playing track")
        return song
    }

    private data class Probe(
        val forThisSong: Boolean,
        val entries: Int,
        val loading: Boolean,
    )

    /**
     * Starts the lyrics fetch for [song] when none has started, then waits (up to [LYRICS_WAIT_MS]) until they are in
     * or known to be missing: `LyricsState.fetchLyrics(song: SongItem, skipCache = false, ...)`, `currentLyricsSongId`,
     * `currentLyricsEntries`, `isLyricsLoading` (all on core-main). Returns true when entries are available.
     */
    internal suspend fun awaitCurrentLyrics(song: SongItem): Boolean {
        withContext(Dispatchers.Main) {
            if (LyricsState.currentLyricsSongId != song.id) LyricsState.fetchLyrics(song)
        }
        var elapsed = 0L
        var sawLoading = false
        while (elapsed <= LYRICS_WAIT_MS) {
            val probe =
                withContext(Dispatchers.Main) {
                    Probe(LyricsState.currentLyricsSongId == song.id, LyricsState.currentLyricsEntries.size, LyricsState.isLyricsLoading)
                }
            sawLoading = sawLoading || probe.loading
            when (LyricsWait.decide(probe.forThisSong, probe.entries, probe.loading, sawLoading, elapsed)) {
                LyricsWait.Decision.READY -> return true
                LyricsWait.Decision.MISS -> return false
                LyricsWait.Decision.WAIT -> Unit
            }
            delay(POLL_MS)
            elapsed += POLL_MS
        }
        return withContext(Dispatchers.Main) { LyricsState.currentLyricsEntries.isNotEmpty() }
    }

    private class CurrentView(
        val songId: String?,
        val provider: String?,
        val offsetMs: Int,
        val entries: List<LyricsEntry>,
    )

    /** Core-main only. */
    private fun readCurrent(): CurrentView =
        CurrentView(
            LyricsState.currentLyricsSongId,
            LyricsState.currentLyricsProvider,
            LyricsState.currentLyricsOffsetMs,
            LyricsState.currentLyricsEntries,
        )

    // --- lyrics.get ----------------------------------------------------------------------------------------------

    private suspend fun get(trackId: String): JsonElement {
        backendReady()
        PlayerApi.ensureCoreDefaults()
        val playing = withContext(Dispatchers.Main) { DesktopPlayerState.currentTrack }
        if (playing?.id == trackId) {
            awaitCurrentLyrics(playing)
            val view = withContext(Dispatchers.Main) { readCurrent() }
            val entries = if (view.songId == trackId) view.entries else emptyList()
            return LyricsMapper.lyrics(trackId, view.provider.takeIf { entries.isNotEmpty() }, if (view.songId == trackId) view.offsetMs else 0, entries)
        }
        return getOther(trackId)
    }

    private suspend fun repository(): LyricsRepository = withContext(Dispatchers.IO) { GlobalContext.get().get<LyricsRepository>() }

    /**
     * Lyrics of a track that is not playing, straight from the repository (cache first, then the user's provider
     * order): `LyricsRepository.getLyrics(videoId, title, artist, duration: Long?, album: String? = null, ...)`
     * returns `LyricsWithProvider?` (text and provider name); `getLyricsOffset(videoId): Int`.
     */
    private suspend fun getOther(trackId: String): JsonElement {
        val song = PlayerApi.resolve(listOf(trackId)).first()
        val repo = repository()
        val found =
            withTimeoutOrNull(LYRICS_WAIT_MS) {
                withContext(Dispatchers.IO) {
                    repo.getLyrics(
                        videoId = song.id,
                        title = song.title,
                        artist = song.artists.joinToString { it.name },
                        duration = song.duration?.takeIf { it > 0 }?.times(1000L),
                        album = song.album?.name,
                    )
                }
            } ?: return LyricsMapper.lyrics(trackId, null, 0, emptyList())
        val document = LyricsUtils.parseMlxDocument(found.lyrics)
        val entries = document?.lines ?: LyricsUtils.parseLyrics(found.lyrics)
        val offset = withContext(Dispatchers.IO) { runCatching { repo.getLyricsOffset(trackId) }.getOrDefault(0) }
        return LyricsMapper.lyrics(trackId, found.provider, offset, entries)
    }

    // --- search / choose -----------------------------------------------------------------------------------------

    private fun candidates(groups: List<LyricsProviderGroup>): List<Pair<String, JsonObject>> =
        groups.flatMap { group ->
            group.results.orEmpty().mapIndexed { index, result ->
                val id = CandidateId.format(group.provider.name, index)
                id to
                    jsonOf(
                        "provider" to JsonPrimitive(result.providerName.ifBlank { group.provider.name }),
                        "synced" to JsonPrimitive(LyricsMapper.syncedOf(result.timingKind)),
                        "preview" to JsonPrimitive(result.preview),
                        "id" to JsonPrimitive(id),
                    )
            }
        }

    /**
     * `LyricsState.fetchProviderResults(song, customTitle, customArtist)` starts one request per provider;
     * `providerSearch.groups` (StateFlow<List<LyricsProviderGroup>>) fills in until no group is `loading`.
     */
    private suspend fun search(
        trackId: String,
        query: String?,
    ): JsonElement {
        val song = currentSong(trackId)
        val search =
            withContext(Dispatchers.Main) {
                LyricsState.fetchProviderResults(song, customTitle = query?.takeIf { it.isNotBlank() })
                LyricsState.providerSearch
            } ?: throw ApiException("internal", "provider search did not start")
        val groups = withTimeoutOrNull(SEARCH_WAIT_MS) { search.groups.first { list -> list.none { it.loading } } } ?: search.groups.value
        return buildJsonObject { put("candidates", JsonArray(candidates(groups).map { it.second })) }
    }

    private fun resolveCandidate(
        groups: List<LyricsProviderGroup>,
        candidateId: String,
    ): LyricsProviderResult? {
        val ref = CandidateId.parse(candidateId) ?: return null
        return groups.firstOrNull { it.provider.name == ref.provider }?.results?.getOrNull(ref.index)
    }

    /** `LyricsState.selectProviderResult(song: SongItem, result: LyricsProviderResult)`; the new lyrics arrive on the topic. */
    private suspend fun choose(
        trackId: String,
        candidateId: String,
    ): JsonElement {
        val song = currentSong(trackId)
        withContext(Dispatchers.Main) {
            val groups = LyricsState.providerSearch?.groups?.value ?: throw ApiException("not_found", "no search results: call lyrics.search first")
            val result = resolveCandidate(groups, candidateId) ?: throw ApiException("not_found", "unknown candidateId")
            LyricsState.selectProviderResult(song, result)
        }
        return emptyResult()
    }

    // --- offset / translate / romanize / edit --------------------------------------------------------------------

    /** The playing track: `LyricsState.updateLyricsOffset(offsetMs: Int)`; another track: `LyricsRepository.setLyricsOffset(videoId, offsetMs): Boolean`. */
    private suspend fun setOffset(
        trackId: String,
        ms: Int,
    ): JsonElement {
        if (ms !in -OFFSET_LIMIT_MS..OFFSET_LIMIT_MS) badRequest("ms must be within +-$OFFSET_LIMIT_MS")
        backendReady()
        val playing = withContext(Dispatchers.Main) { DesktopPlayerState.currentTrack }
        if (playing?.id == trackId) {
            withContext(Dispatchers.Main) { LyricsState.updateLyricsOffset(ms) }
        } else if (!withContext(Dispatchers.IO) { repository().setLyricsOffset(trackId, ms) }) {
            throw ApiException("internal", "lyrics offset could not be saved")
        }
        return emptyResult()
    }

    /** `lyricsAiTargetLanguage` is set first; `LyricsState.setManualLyricsTranslation(enabled: Boolean)` needs the lyrics loaded. */
    private suspend fun translate(
        trackId: String,
        on: Boolean,
        target: String?,
    ): JsonElement {
        val song = currentSong(trackId)
        target?.takeIf { it.isNotBlank() }?.let { Settings.shared.lyricsAiTargetLanguage = it }
        awaitCurrentLyrics(song)
        withContext(Dispatchers.Main) { LyricsState.setManualLyricsTranslation(on) }
        return emptyResult()
    }

    /** `lyricsRomanizationEnabled` first, then `LyricsState.refreshEditedLyrics(song: SongItem)` reloads with the new setting. */
    private suspend fun romanize(
        trackId: String,
        on: Boolean,
    ): JsonElement {
        val song = currentSong(trackId)
        Settings.shared.lyricsRomanizationEnabled = on
        withContext(Dispatchers.Main) { LyricsState.refreshEditedLyrics(song) }
        return emptyResult()
    }

    /** `LyricsState.saveCustomLyrics(song: SongItem, lyricsText: String)`. */
    private suspend fun edit(
        trackId: String,
        lrc: String,
    ): JsonElement {
        val song = currentSong(trackId)
        withContext(Dispatchers.Main) { LyricsState.saveCustomLyrics(song, lrc) }
        return emptyResult()
    }

    // --- providers -----------------------------------------------------------------------------------------------

    /** `{order: string[], enabled: {name: boolean}}` over the user-configurable providers. */
    private suspend fun providers(): JsonElement {
        backendReady()
        val (order, enabled) = withContext(Dispatchers.Main) { LyricsState.lyricsProviderOrder to LyricsState.lyricsEnabledProviders }
        return buildJsonObject {
            put("order", JsonArray(order.map { JsonPrimitive(it.name) }))
            put("enabled", JsonObject(LyricsProvider.userConfigurable.associate { it.name to JsonPrimitive(it in enabled) }))
        }
    }

    private fun names(value: JsonElement?): List<String>? =
        (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

    /** `enabled` as the protocol sends it (`{name: boolean}`) or as a list of enabled names. */
    private fun enabledNames(value: JsonElement?): List<String>? =
        when (value) {
            is JsonObject -> value.entries.filter { (_, v) -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true" }.map { it.key }
            is JsonArray -> names(value)
            else -> null
        }

    /** Writes `Settings.lyricsProviderOrder` (csv) and `lyricsEnabledProviders` (csv; `NONE` when nothing is enabled). */
    private suspend fun setProviders(params: JsonObject): JsonElement {
        backendReady()
        val order = names(params["order"])?.let { CandidateId.validOrder(it) ?: badRequest("order has an unknown or repeated provider") }
        val enabled = enabledNames(params["enabled"])?.let { list -> list.takeIf { l -> l.all { it in CandidateId.configurable } } ?: badRequest("enabled has an unknown provider") }
        if (order == null && enabled == null) badRequest("order or enabled is required")
        withContext(Dispatchers.Main) {
            if (order != null) Settings.shared.lyricsProviderOrder = order.joinToString(",")
            if (enabled != null) Settings.shared.lyricsEnabledProviders = if (enabled.isEmpty()) "NONE" else enabled.joinToString(",")
        }
        return emptyResult()
    }

    // --- topic ---------------------------------------------------------------------------------------------------

    /** What the topic watches; equal values mean nothing to send. */
    private data class Watched(
        val trackId: String?,
        val songId: String?,
        val provider: String?,
        val offsetMs: Int,
        val entries: List<LyricsEntry>,
    )

    /** Core-main only. */
    private fun watchedNow(): Watched =
        Watched(
            DesktopPlayerState.currentTrack?.id,
            LyricsState.currentLyricsSongId,
            LyricsState.currentLyricsProvider,
            LyricsState.currentLyricsOffsetMs,
            LyricsState.currentLyricsEntries,
        )

    private fun payload(w: Watched): JsonObject = LyricsMapper.lyrics(w.songId.orEmpty(), w.provider, w.offsetMs, w.entries)

    /**
     * `lyrics.snapshot` = the playing track's lyrics (waits for them) or null; then `lyrics.updated` whenever the
     * entries, offset or provider of the playing track change (at most every 250 ms) and `lyrics.cleared` when they
     * go away. While a client is subscribed the core fetches the lyrics of every new track, because LyricsState
     * itself only does so with the v1 lyrics drawer open.
     */
    @OptIn(FlowPreview::class)
    val lyricsTopic: Topic =
        topic { _, emit ->
            backendReady()
            PlayerApi.ensureCoreDefaults()
            coroutineScope {
                launch(Dispatchers.Main) {
                    snapshotFlow { DesktopPlayerState.currentTrack }
                        .map { it?.takeIf { song -> song.id.isNotBlank() } }
                        .distinctUntilChanged { a, b -> a?.id == b?.id }
                        .collect { song ->
                            if (song != null && LyricsState.currentLyricsSongId != song.id) {
                                runCatching { LyricsState.fetchLyrics(song) }.onFailure { CoreLog.error("lyrics fetch failed to start", it) }
                            }
                        }
                }
                val playing = withContext(Dispatchers.Main) { DesktopPlayerState.currentTrack }
                if (playing != null) awaitCurrentLyrics(playing)
                val first = withContext(Dispatchers.Main) { watchedNow() }
                val firstShown = first.trackId != null && first.songId == first.trackId && first.entries.isNotEmpty()
                emit("lyrics.snapshot", if (firstShown) payload(first) else kotlinx.serialization.json.JsonNull)
                var shown = firstShown
                var last = first
                withContext(Dispatchers.Main) {
                    snapshotFlow { watchedNow() }
                        .sample(SAMPLE_MS)
                        .distinctUntilChanged()
                        .collect { now ->
                            if (now == last) return@collect
                            last = now
                            val present = now.trackId != null && now.songId == now.trackId && now.entries.isNotEmpty()
                            if (present) {
                                shown = true
                                emit("lyrics.updated", payload(now))
                            } else if (shown && now.entries.isEmpty()) {
                                shown = false
                                emit("lyrics.cleared", emptyResult())
                            }
                        }
                }
            }
        }

    private const val SAMPLE_MS = 250L
}
