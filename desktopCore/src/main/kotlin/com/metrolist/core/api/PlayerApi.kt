package com.metrolist.core.api

import androidx.compose.runtime.snapshotFlow
import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreLog
import com.metrolist.core.api.output.OutputPrefs
import com.metrolist.core.api.output.outputKindOf
import com.metrolist.core.api.pages.loadAlbum
import com.metrolist.core.api.queue.QueueTracker
import com.metrolist.core.api.queue.canNext
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.bool
import com.metrolist.core.server.double
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.int
import com.metrolist.core.server.obj
import com.metrolist.core.server.requireDouble
import com.metrolist.core.server.requireLong
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.core.server.topic
import com.metrolist.core.server.badRequest
import com.metrolist.core.api.tracksource.TrackSourceOverrides
import com.metrolist.desktop.state.playback.PlaybackVideoPolicy
import com.metrolist.desktop.state.playback.PlayerState as DesktopPlayerState
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.sourceOf
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * api.md 3.6 (M0 subset) and the `player` topic (section 4), bridged from the v1 PlayerState: commands run on
 * core-main, state comes from `snapshotFlow` over its Compose states plus its StateFlows.
 */
object PlayerApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "player.play" to handler { params, _ -> play(params) },
            "player.toggle" to main { DesktopPlayerState.togglePlay() },
            "player.pause" to main { DesktopPlayerState.pause() },
            "player.resume" to main { DesktopPlayerState.play() },
            "player.next" to handler { _, _ -> next() },
            "player.prev" to main { DesktopPlayerState.skipPrevious() },
            "player.seek" to handler { params, _ -> seek(params.requireLong("ms")) },
            "player.seekRelative" to
                handler { params, _ ->
                    val delta = params.requireLong("deltaMs")
                    onMain { DesktopPlayerState.seekRelative(delta) }
                },
            "player.seekPercent" to
                handler { params, _ ->
                    val pct = params.requireDouble("pct").coerceIn(0.0, 100.0)
                    onMain { DesktopPlayerState.seekTo((pct / 100.0).toFloat()) }
                },
            "player.setVolume" to
                handler { params, _ ->
                    val v = params.requireDouble("v").coerceIn(0.0, 1.0)
                    onMain { DesktopPlayerState.setVolumeLevel(v.toFloat()) }
                },
            "player.setShuffle" to
                handler { params, _ ->
                    val on = params.bool("on") ?: badRequest("missing boolean param 'on'")
                    onMain { if (DesktopPlayerState.shuffleEnabled != on) DesktopPlayerState.toggleShuffle() }
                },
            "player.setRepeat" to
                handler { params, _ ->
                    val mode = RepeatMode.entries.firstOrNull { it.name.equals(params.requireStr("mode"), ignoreCase = true) }
                    if (mode == null) badRequest("mode must be off, all or one")
                    onMain { DesktopPlayerState.repeatMode = mode }
                },
            "player.cycleRepeat" to main { DesktopPlayerState.cycleRepeatMode() },
            "player.setSpeed" to
                handler { params, _ ->
                    val speed = params.requireDouble("speed").toFloat()
                    val pitch = params.str("pitch")?.toFloatOrNull() ?: (params["pitch"] as? JsonPrimitive)?.content?.toFloatOrNull()
                    onMain {
                        DesktopPlayerState.setPlaybackSpeed(speed)
                        if (pitch != null) DesktopPlayerState.setPlaybackPitch(pitch)
                    }
                },
            "player.mute" to
                handler { params, _ ->
                    val on = params.bool("on") ?: badRequest("missing boolean param 'on'")
                    onMain { mute(on) }
                },
            "player.sleepTimer" to handler { params, _ -> sleepTimer(params) },
        )

    // --- core defaults -------------------------------------------------------------------------------------------

    private val coreDefaultsApplied = AtomicBoolean(false)

    /**
     * One-time, idempotent setup the first time a player call arrives. (1) The v2 core has no video surface until
     * `player.video` (M5): a music video asked for as a video resource is a muxed progressive stream that, in the
     * headless core, does not seek and never reaches EOF (INT-W1: seek ignored on MV tracks, no auto-advance), so ask
     * for audio only. M5 must set `musicVideoPlaybackEnabled` back when it adds the video surface. (2) Re-apply the
     * output device chosen with `player.output.set` in an earlier run.
     */
    internal fun ensureCoreDefaults() {
        if (!coreDefaultsApplied.compareAndSet(false, true)) return
        // Runtime only: the settings file is shared with v1, so the stored value is never written here.
        if (!PlaybackVideoPolicy.audioOnly) {
            PlaybackVideoPolicy.audioOnly = true
            CoreLog.info("music videos play audio-only: the core has no video surface yet (runtime override)")
        }
        OutputPrefs.restore()?.let { AudioRenderer.setPreferredOutputDevice(it) }
    }

    // --- seek ----------------------------------------------------------------------------------------------------

    @Volatile
    private var seekVerifyJob: Job? = null

    /**
     * `seekToPosition(positionMs: Long)` (PlayerState) is the normal path. A background check follows (INT-W1: seek had
     * no effect on MV tracks): when the audio player is nowhere near the target, the seek is repeated straight on
     * `MusicPlayer.seekTo(position: Long)`, skipping the PlayerState guards and the resume bookkeeping.
     */
    private suspend fun seek(ms: Long): JsonElement {
        backendReady()
        ensureCoreDefaults()
        val target = ms.coerceAtLeast(0L)
        val trackId =
            withContext(Dispatchers.Main) {
                DesktopPlayerState.seekToPosition(target)
                DesktopPlayerState.currentTrack?.id
            }
        seekVerifyJob?.cancel()
        seekVerifyJob =
            CoreBackend.scope.launch {
                delay(SEEK_VERIFY_DELAY_MS)
                // INT-W5: a seek sent while the stream is still loading (player.state already shows the track) is dropped,
                // and the track then starts at 0. Wait for the load to finish (bounded), then check and repeat the seek.
                val waitUntil = System.currentTimeMillis() + SEEK_PENDING_MAX_MS
                while (System.currentTimeMillis() < waitUntil) {
                    val loaded =
                        withContext(Dispatchers.Main) {
                            val p = DesktopPlayerState
                            p.currentTrack?.id != trackId || (!p.isPlaybackLoading && p.player.duration.value > 0L)
                        }
                    if (loaded) break
                    delay(SEEK_PENDING_POLL_MS)
                }
                withContext(Dispatchers.Main) {
                    val p = DesktopPlayerState
                    if (p.currentTrack?.id != trackId) return@withContext
                    // MusicPlayer.currentPlaybackPositionMs(): Long, MusicPlayer.duration: StateFlow<Long> (ms, 0 until known).
                    val actual = p.player.currentPlaybackPositionMs()
                    val duration = p.player.duration.value
                    if (!SeekCheck.landed(target, actual, duration)) {
                        CoreLog.info("seek to $target ms did not land (at $actual ms, duration $duration ms): repeating on the audio player")
                        // MusicPlayer.seekTo(position: Long)
                        p.player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                    }
                }
            }
        return emptyResult()
    }

    // --- next ----------------------------------------------------------------------------------------------------

    /** `skipNext()`; at the end of a queue with autoplay on, the radio loads asynchronously, so wait for the new tracks. */
    private suspend fun next(): JsonElement {
        backendReady()
        ensureCoreDefaults()
        val (before, states) =
            withContext(Dispatchers.Main) {
                val flow = DesktopPlayerState.queue.state
                val snapshot = flow.value
                val waits = SeekCheck.autoplayLoads(snapshot.currentIndex, snapshot.entries.size, DesktopPlayerState.repeatMode, Settings.shared.autoLoadMore)
                DesktopPlayerState.skipNext()
                (if (waits) snapshot.entries.size else -1) to flow
            }
        if (before >= 0) withTimeoutOrNull(AUTOPLAY_WAIT_MS) { states.first { it.entries.size > before } }
        return emptyResult()
    }

    // --- mute ----------------------------------------------------------------------------------------------------

    /** Core-main only. `volume: Float` (val), `previousVolume: Float` (var), `setVolumeLevel(level: Float)`. */
    private fun mute(on: Boolean) {
        val p = DesktopPlayerState
        if (on) {
            if (p.volume > 0f) {
                p.previousVolume = p.volume
                p.setVolumeLevel(0f)
            }
        } else if (p.volume <= 0f) {
            p.setVolumeLevel(p.previousVolume.takeIf { it > 0f } ?: DEFAULT_UNMUTE_VOLUME)
        }
    }

    // --- sleep timer ---------------------------------------------------------------------------------------------

    @Volatile
    private var sleepAnchor: Long? = null

    /** `startSleepTimer(durationMs, stopAfterCurrentSong, fadeOut, finishCurrentSong = false)` and `clearSleepTimer()` on core-main. */
    private suspend fun sleepTimer(params: JsonObject): JsonElement {
        backendReady()
        if (params.bool("cancel") == true) {
            withContext(Dispatchers.Main) {
                DesktopPlayerState.clearSleepTimer()
                sleepAnchor = null
            }
            return emptyResult()
        }
        if (params.bool("endOfTrack") == true) {
            withContext(Dispatchers.Main) {
                sleepAnchor = null
                DesktopPlayerState.startSleepTimer(0L, stopAfterCurrentSong = true, fadeOut = false)
            }
            return emptyResult()
        }
        val minutes = params.double("minutes")?.takeIf { it > 0.0 && it.isFinite() } ?: badRequest("sleepTimer needs minutes > 0, endOfTrack or cancel")
        val durationMs = (minutes * 60_000.0).roundToLong()
        val deadline = System.currentTimeMillis() + durationMs
        withContext(Dispatchers.Main) {
            sleepAnchor = deadline
            DesktopPlayerState.startSleepTimer(durationMs, stopAfterCurrentSong = false, fadeOut = false)
        }
        return buildJsonObject { put("deadline", deadline) }
    }

    private fun main(block: () -> Unit): Handler =
        handler { _, _ ->
            backendReady()
            onMain(block)
        }

    private suspend fun onMain(block: () -> Unit): JsonElement {
        backendReady()
        withContext(Dispatchers.Main) { block() }
        return emptyResult()
    }

    // --- player.play ---------------------------------------------------------------------------------------------

    private suspend fun play(params: JsonObject): JsonElement {
        backendReady()
        val context = params.obj("context") ?: badRequest("missing 'context'")
        val kind = context.str("kind")
        if (kind == "radio") {
            // startRadio(song: SongItem): replaces the queue with a radio seeded by the song, on core-main.
            val seed = resolve(listOf(context.str("id") ?: badRequest("radio context needs 'id'"))).first()
            withContext(Dispatchers.Main) { DesktopPlayerState.startRadio(seed) }
            return emptyResult()
        }
        val songs = resolveContext(context)
        if (songs.isEmpty()) throw ApiException("not_found", "nothing to play")
        val index =
            params.int("startIndex")
                ?: params.str("startId")?.let { id -> songs.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                ?: 0
        val track = songs[index.coerceIn(0, songs.lastIndex)]
        val shuffle = params.bool("shuffle")
        // playTrack(track, list, songsOnly, shuffleModeEnabled): replaces the queue; artist radios stay songs-only.
        withContext(Dispatchers.Main) { DesktopPlayerState.playTrack(track, songs, songsOnly = kind == "artist", shuffleModeEnabled = shuffle) }
        return emptyResult()
    }

    /** Plays [ids] as a fresh queue (used by `app.openUrl` for a video link). */
    internal suspend fun playIds(ids: List<String>) {
        backendReady()
        val songs = resolve(ids)
        val track = songs.firstOrNull() ?: throw ApiException("not_found", "nothing to play")
        withContext(Dispatchers.Main) { DesktopPlayerState.playTrack(track, songs) }
    }

    /**
     * The songs of a play context (api.md 3.6), shared by `player.play` and `queue.addContext`. `radio` yields the
     * station's songs here (`player.play` starts the radio through PlayerState instead).
     */
    internal suspend fun resolveContext(context: JsonObject): List<SongItem> {
        backendReady()
        return TrackSourceOverrides.applyAsync(contextSongs(context))
    }

    private suspend fun contextSongs(context: JsonObject): List<SongItem> =
        when (context.str("kind")) {
            "tracks" -> {
                val ids = (context["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                resolve(ids ?: badRequest("context.ids must be an array of ids"), overrides = false)
            }

            "library" -> {
                withContext(Dispatchers.IO) {
                    LibraryApi.songs(context.str("list") ?: "songs", context.obj("sort"), context.obj("filter")).map { it.toSongItem() }
                }
            }

            "album" -> {
                (loadAlbum(context.requireStr("id")) ?: throw ApiException("not_found", "album not found")).songs
            }

            "playlist" -> {
                playlistSongs(context.requireStr("id"))
            }

            "artist" -> {
                // getArtistSongs(channelId, page = null, onFailure): List<SongItem>
                GlobalYouTubeRepository.instance.getArtistSongs(context.requireStr("id"))
            }

            "radio" -> {
                GlobalYouTubeRepository.instance.startRadio(context.requireStr("id"))
            }

            "userAlbum" -> {
                val id = context.requireStr("id").removePrefix("lp:")
                withContext(Dispatchers.IO) {
                    // PlaylistDao.playlistSongs(playlistId: String): Flow<List<PlaylistSong>>; PlaylistSong.toSongItem() keeps the entry ids.
                    GlobalContext.get().get<DatabaseDao>().playlistSongs(id).first().map { it.toSongItem() }
                }
            }

            else -> {
                badRequest("unknown context.kind")
            }
        }

    /** All songs of a playlist for playback: the first page that has songs, then continuations up to [MAX_PLAYLIST_SONGS]. */
    private suspend fun playlistSongs(id: String): List<SongItem> {
        val repo = GlobalYouTubeRepository.instance
        // getPlaylistPlaybackPage(playlistId): PlaylistPage; continuePlaylistPage(page, applyContentFilters): PlaylistPage
        var page = repo.getPlaylistPlaybackPage(id)
        while (page.hasMore && page.songs.size < MAX_PLAYLIST_SONGS) page = repo.continuePlaylistPage(page, false)
        return page.songs
    }

    /**
     * Library rows where known, YouTube metadata for other ids, and a bare item as the last resort (it still plays). With
     * [overrides] (playback, downloads) the per-track source choice of `track.setSource` replaces a song (M3-2); callers
     * that store or describe the id itself (user albums, `track.info`) pass false.
     */
    internal suspend fun resolve(
        ids: List<String>,
        overrides: Boolean = true,
    ): List<SongItem> {
        val songs =
            withContext(Dispatchers.IO) {
                val stored = GlobalContext.get().get<DatabaseDao>().getSongsByIds(ids).associateBy { it.id }
                ids.map { id -> stored[id]?.toSongItem() ?: remoteSong(id) ?: SongItem(id = id, title = id, thumbnail = null, artists = emptyList<ArtistTiny>(), album = null) }
            }
        return if (overrides) TrackSourceOverrides.applyAsync(songs) else songs
    }

    private suspend fun remoteSong(id: String): SongItem? {
        if (sourceOf(id) != SourceId.YT) return null
        return withTimeoutOrNull(REMOTE_LOOKUP_MS) {
            runCatching { GlobalYouTubeRepository.instance.getSongMetadata(id) }.getOrNull()
        }
    }

    // --- player topic --------------------------------------------------------------------------------------------

    private val player get() = DesktopPlayerState

    private fun stateNow(): PlayerState {
        val p = player
        val track = p.currentTrack
        val queue = p.queue.state.value
        val repeat = p.repeatMode
        return PlayerState(
            track = track?.toTrack(liked = p.isLiked(track.id)),
            playing = p.isPlaying,
            buffering = p.isPlaybackLoading,
            reconnecting = p.isReconnecting,
            posMs = p.playbackPosition.value,
            durMs = p.playbackDuration.value,
            bufMs = p.playbackBufferedPosition.value,
            at = System.currentTimeMillis(),
            volume = p.volume.toDouble(),
            muted = p.volume <= 0f,
            shuffle = p.shuffleEnabled,
            repeat = repeat.name.lowercase(),
            speed = round2(Settings.shared.playbackSpeed.toDouble()),
            pitch = round2(Settings.shared.playbackPitch.toDouble()),
            sleep =
                if (p.sleepTimerEnabled) {
                    val endOfTrack = p.sleepTimerStopAfterCurrentSong
                    SleepInfo(
                        deadline = if (endOfTrack) null else SleepDeadline.deadline(System.currentTimeMillis(), p.sleepTimerTimeLeftMs.value, sleepAnchor),
                        endOfTrack = endOfTrack,
                    )
                } else {
                    null
                },
            output = OutputPrefs.chosen?.let { OutputInfo(id = it, name = it, kind = outputKindOf(it)) },
            eq = EqSummary(enabled = false, profileName = ""),
            source = track?.let { sourceOf(it.id).key },
            canPrev = p.canSkipPrevious,
            // Autoplay: at the end of a YouTube queue the core loads a radio, so "next" stays enabled (see PlaybackQueuePolicy).
            canNext = canNext(queue.currentIndex, queue.entries.size, repeat, Settings.shared.autoLoadMore, track?.id),
        )
    }

    private fun PlayerState.withoutPosition(): PlayerState = copy(posMs = 0, bufMs = 0, at = 0)

    private fun stateChanges(): Flow<PlayerState> =
        merge(
            snapshotFlow { stateNow() },
            player.queue.state.map { stateNow() },
            player.playbackDuration.map { stateNow() },
            player.volumeState.map { stateNow() },
            player.player.playbackSpeed.map { stateNow() },
        )

    private fun round2(value: Double): Double = (value * 100.0).roundToLong() / 100.0

    /**
     * `player.state` is the full state. api.md 4 says "minus position", but the UI clock reads `posMs` from it and
     * showed NaN without it (INT-W1), so the position fields stay in; `player.position` still carries the 1 Hz updates.
     */
    private fun stateEvent(state: PlayerState): JsonElement = state.toJson()

    private fun positionEvent(state: PlayerState): JsonElement =
        buildJsonObject {
            put("posMs", state.posMs)
            put("durMs", state.durMs)
            put("bufMs", state.bufMs)
            put("playing", state.playing)
            put("rate", state.speed)
            put("at", state.at)
        }

    val playerTopic: Topic =
        topic { _, emit ->
            backendReady()
            ensureCoreDefaults()
            QueueTracker.ensureStarted()
            coroutineScope {
                val first = withContext(Dispatchers.Main) { stateNow() }
                emit("player.snapshot", first.toJson())
                var last = first.withoutPosition()
                // api.md 1.9: position on state change, every 1000 ms while playing, never faster than 4 Hz.
                val kick = Channel<Unit>(Channel.CONFLATED)
                launch(Dispatchers.Main) {
                    stateChanges().collect { state ->
                        val core = state.withoutPosition()
                        if (core != last) {
                            last = core
                            emit("player.state", stateEvent(state))
                            kick.trySend(Unit)
                        }
                    }
                }
                launch(Dispatchers.Main) {
                    var lastPos = first.posMs
                    var lastAt = System.currentTimeMillis()
                    player.playbackPosition.collect { pos ->
                        val now = System.currentTimeMillis()
                        val speed = Settings.shared.playbackSpeed.toDouble()
                        val expected = if (player.isPlaying) lastPos + ((now - lastAt) * speed).toLong() else lastPos
                        if (abs(pos - expected) > SEEK_JUMP_MS) kick.trySend(Unit)
                        lastPos = pos
                        lastAt = now
                    }
                }
                launch {
                    while (isActive) {
                        delay(POSITION_TICK_MS)
                        if (player.isPlaying) kick.trySend(Unit)
                    }
                }
                launch {
                    for (signal in kick) {
                        val state = withContext(Dispatchers.Main) { stateNow() }
                        emit("player.position", positionEvent(state))
                        delay(MIN_POSITION_GAP_MS)
                    }
                }
                launch(Dispatchers.Main) {
                    snapshotFlow { if (player.showPlayerError) player.playerErrorMessage else null }
                        .filterNotNull()
                        .distinctUntilChanged()
                        .collect { message ->
                            CoreLog.info("player error: $message")
                            emit(
                                "player.error",
                                buildJsonObject {
                                    put("code", "internal")
                                    put("i18n", "")
                                    put("msg", message)
                                    put("trackId", player.currentTrack?.id ?: "")
                                    put("skipped", false)
                                },
                            )
                        }
                }
            }
        }

    private const val MAX_PLAYLIST_SONGS = 5_000
    private const val REMOTE_LOOKUP_MS = 8_000L
    private const val SEEK_JUMP_MS = 1_500L
    private const val POSITION_TICK_MS = 1_000L
    private const val MIN_POSITION_GAP_MS = 250L
    private const val SEEK_VERIFY_DELAY_MS = 450L
    private const val SEEK_PENDING_MAX_MS = 30_000L
    private const val SEEK_PENDING_POLL_MS = 200L
    private const val AUTOPLAY_WAIT_MS = 10_000L
    private const val DEFAULT_UNMUTE_VOLUME = 0.7f
}

/** Pure checks around `player.seek` and `player.next`. */
object SeekCheck {
    const val TOLERANCE_BEHIND_MS = 1_500L
    const val TOLERANCE_AHEAD_MS = 6_000L

    /**
     * True when [actualMs] is where a seek to [targetMs] puts the audio player a moment later: playback may have moved
     * on from the target ([TOLERANCE_AHEAD_MS]) but not stayed behind it. The target is clamped to [durationMs] when
     * known, and at the very end the position may already have been reset (end of track), which counts as landed.
     */
    fun landed(
        targetMs: Long,
        actualMs: Long,
        durationMs: Long,
    ): Boolean {
        val target = if (durationMs > 0L) targetMs.coerceIn(0L, durationMs) else targetMs.coerceAtLeast(0L)
        if (durationMs > 0L && target >= durationMs - TOLERANCE_AHEAD_MS) return true
        return actualMs in (target - TOLERANCE_BEHIND_MS)..(target + TOLERANCE_AHEAD_MS)
    }

    /** True when "next" from [currentIndex] reaches the end of the queue and autoplay will append a radio (repeat off). */
    fun autoplayLoads(
        currentIndex: Int,
        size: Int,
        repeat: RepeatMode,
        autoLoadMore: Boolean,
    ): Boolean = autoLoadMore && repeat == RepeatMode.OFF && size > 0 && currentIndex >= size - 1
}

/** Sleep timer deadline (epoch ms) shown in `player.state.sleep` and returned by `player.sleepTimer`. */
object SleepDeadline {
    /** The [anchor] set when the timer started wins (stable across state emissions); else now + time left, or null when nothing is left. */
    fun deadline(
        nowMs: Long,
        timeLeftMs: Long,
        anchor: Long?,
    ): Long? = anchor ?: timeLeftMs.takeIf { it > 0L }?.let { nowMs + it }
}
