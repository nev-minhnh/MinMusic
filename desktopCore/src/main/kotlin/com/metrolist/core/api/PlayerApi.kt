package com.metrolist.core.api

import androidx.compose.runtime.snapshotFlow
import com.metrolist.core.CoreLog
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.bool
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.int
import com.metrolist.core.server.obj
import com.metrolist.core.server.requireDouble
import com.metrolist.core.server.requireLong
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.core.server.topic
import com.metrolist.core.server.unsupported
import com.metrolist.core.server.badRequest
import com.metrolist.desktop.state.playback.PlayerState as DesktopPlayerState
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import kotlin.math.abs

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
            "player.next" to main { DesktopPlayerState.skipNext() },
            "player.prev" to main { DesktopPlayerState.skipPrevious() },
            "player.seek" to
                handler { params, _ ->
                    val ms = params.requireLong("ms")
                    onMain { DesktopPlayerState.seekToPosition(ms) }
                },
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
        )

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
        val songs: List<SongItem> =
            when (context.str("kind")) {
                "tracks" -> {
                    val ids = (context["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                    resolve(ids ?: badRequest("context.ids must be an array of ids"))
                }

                "library" -> {
                    withContext(Dispatchers.IO) {
                        LibraryApi.songs(context.str("list") ?: "songs", context.obj("sort"), context.obj("filter")).map { it.toSongItem() }
                    }
                }

                "album", "playlist", "artist", "radio", "userAlbum" -> {
                    unsupported("play context '${context.str("kind")}' is not available in M0")
                }

                else -> {
                    badRequest("unknown context.kind")
                }
            }
        if (songs.isEmpty()) throw ApiException("not_found", "nothing to play")
        val index =
            params.int("startIndex")
                ?: params.str("startId")?.let { id -> songs.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                ?: 0
        val track = songs[index.coerceIn(0, songs.lastIndex)]
        val shuffle = params.bool("shuffle")
        withContext(Dispatchers.Main) { DesktopPlayerState.playTrack(track, songs, shuffleModeEnabled = shuffle) }
        return emptyResult()
    }

    /** Library rows where known, YouTube metadata for other ids, and a bare item as the last resort (it still plays). */
    private suspend fun resolve(ids: List<String>): List<SongItem> =
        withContext(Dispatchers.IO) {
            val stored = GlobalContext.get().get<DatabaseDao>().getSongsByIds(ids).associateBy { it.id }
            ids.map { id -> stored[id]?.toSongItem() ?: remoteSong(id) ?: SongItem(id = id, title = id, thumbnail = null, artists = emptyList<ArtistTiny>(), album = null) }
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
            muted = false,
            shuffle = p.shuffleEnabled,
            repeat = repeat.name.lowercase(),
            speed = Settings.shared.playbackSpeed.toDouble(),
            pitch = Settings.shared.playbackPitch.toDouble(),
            eq = EqSummary(enabled = false, profileName = ""),
            source = track?.let { sourceOf(it.id).key },
            canPrev = p.canSkipPrevious,
            canNext = queue.currentIndex + 1 < queue.entries.size || repeat == RepeatMode.ALL,
        )
    }

    private fun PlayerState.withoutPosition(): PlayerState = copy(posMs = 0, bufMs = 0, at = 0)

    private fun stateChanges(): Flow<PlayerState> =
        merge(
            snapshotFlow { stateNow() },
            player.queue.state.map { stateNow() },
            player.playbackDuration.map { stateNow() },
        )

    /** `player.state` is the full state minus position. */
    private fun stateEvent(state: PlayerState): JsonElement = JsonObject(state.toJson().jsonObject - setOf("posMs", "bufMs", "at"))

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

    private const val REMOTE_LOOKUP_MS = 8_000L
    private const val SEEK_JUMP_MS = 1_500L
    private const val POSITION_TICK_MS = 1_000L
    private const val MIN_POSITION_GAP_MS = 250L
}
