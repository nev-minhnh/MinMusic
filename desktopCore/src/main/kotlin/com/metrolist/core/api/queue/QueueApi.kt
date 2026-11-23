package com.metrolist.core.api.queue

import com.metrolist.core.api.PlayerApi
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.bool
import com.metrolist.core.server.int
import com.metrolist.core.server.long
import com.metrolist.core.server.obj
import com.metrolist.core.server.requireBool
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.topic
import com.metrolist.desktop.state.playback.PlayerState as DesktopPlayerState
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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

/**
 * api.md 3.6 (`queue.*`) and the `queue` topic. Every call into the desktop PlayerState runs on core-main.
 *
 * The queue lock (`Settings.shared.isQueueLocked`, default true in v1) is reported (`locked`) and switched
 * (`queue.setLocked`) here but not enforced on `queue.remove` / `queue.move` / `queue.clear`: the lock is a guard
 * against accidental drags in the UI, and the m2-player contract suite edits the queue on a fresh (locked) profile.
 */
object QueueApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "queue.window" to handler { params, _ -> window(params) },
            "queue.add" to handler { params, _ -> add(params) },
            "queue.addContext" to handler { params, _ -> addContext(params) },
            "queue.setAutoplay" to
                handler { params, _ ->
                    Settings.shared.autoLoadMore = params.requireBool("on")
                    emptyResult()
                },
            "queue.remove" to handler { params, _ -> remove(params) },
            "queue.move" to handler { params, _ -> move(params) },
            "queue.select" to handler { params, _ -> select(params) },
            "queue.clear" to handler { params, _ -> clear(params) },
            "queue.radio" to handler { params, _ -> radio(params) },
            "queue.setLocked" to
                handler { params, _ ->
                    Settings.shared.isQueueLocked = params.requireBool("on")
                    emptyResult()
                },
        )

    private suspend fun queueState(): MusicQueueState {
        backendReady()
        return withContext(Dispatchers.Main) { DesktopPlayerState.queue.state.value }
    }

    // --- queue.window --------------------------------------------------------------------------------------------

    private suspend fun window(params: JsonObject): JsonElement {
        val offset = params.int("offset") ?: 0
        val limit = params.int("limit") ?: QueueMapper.MAX_WINDOW
        val state = queueState()
        return QueueMapper.window(state, QueueRevision.observe(state), offset, limit, Settings.shared.isQueueLocked)
    }

    // --- queue.add / queue.addContext ----------------------------------------------------------------------------

    private suspend fun add(params: JsonObject): JsonElement {
        backendReady()
        val ids =
            (params["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                ?: badRequest("ids must be an array of strings")
        val position = QueueMapper.parsePosition(params["position"])
        return addSongs(PlayerApi.resolve(ids), position)
    }

    private suspend fun addContext(params: JsonObject): JsonElement {
        backendReady()
        val context = params.obj("context") ?: badRequest("missing 'context'")
        val position = QueueMapper.parsePosition(params["position"])
        return addSongs(PlayerApi.resolveContext(context), position)
    }

    private suspend fun addSongs(
        songs: List<SongItem>,
        position: QueuePosition,
    ): JsonElement {
        val revision =
            withContext(Dispatchers.Main) {
                val player = DesktopPlayerState
                val before = player.queue.state.value
                if (songs.isNotEmpty()) {
                    when (position) {
                        QueuePosition.Next -> {
                            player.playNext(songs)
                        }

                        QueuePosition.End -> {
                            player.addToQueue(songs)
                        }

                        is QueuePosition.At -> {
                            player.addToQueue(songs)
                            moveAdded(before, position.index)
                        }
                    }
                }
                QueueRevision.observe(player.queue.state.value)
            }
        return buildJsonObject { put("revision", revision) }
    }

    /**
     * Moves the songs just appended to the end to slot [requested] (clamped to the old end; before the playing entry
     * is allowed and the playing index follows). Index moves only: `moveQueueItem(from: Int, to: Int, expectedQueue = ...)`
     * has no lock or current-index restriction, unlike the key-based overload. Core-main only.
     */
    private fun moveAdded(
        before: MusicQueueState,
        requested: Int,
    ) {
        val player = DesktopPlayerState
        val base = QueueIndex.addSlot(requested, before.entries.size)
        val keys = QueueMapper.addedKeys(before, player.queue.state.value)
        keys.forEachIndexed { offset, key ->
            val entries = player.queue.state.value.entries
            val from = entries.indexOfFirst { it.key == key }
            val to = base + offset
            if (from >= 0 && from != to && to < entries.size) player.moveQueueItem(from = from, to = to)
        }
    }

    // --- queue.remove / move / select / clear / radio ------------------------------------------------------------

    private fun stringList(value: JsonElement?): List<String>? =
        (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

    private fun refused(): Nothing =
        if (Settings.shared.isQueueLocked) throw ApiException("busy", "queue locked") else throw ApiException("conflict", "queue changed")

    /** `removeQueueItems(indices: Set<Int>, expectedQueue = queue.snapshots.value): QueueRemoval?`; unknown qids are a no-op. */
    private suspend fun remove(params: JsonObject): JsonElement {
        val qids = stringList(params["qids"]) ?: badRequest("qids must be an array of strings")
        backendReady()
        PlayerApi.ensureCoreDefaults()
        withContext(Dispatchers.Main) {
            val indices = QueueIndex.indicesOf(DesktopPlayerState.queue.state.value, qids)
            if (indices.isNotEmpty() && DesktopPlayerState.removeQueueItems(indices) == null) refused()
        }
        return emptyResult()
    }

    /** Checks `expectedRevision` against the revision the UI last saw, then `moveQueueItem(from: Int, to: Int, ...)`. */
    private suspend fun move(params: JsonObject): JsonElement {
        val from = params.int("from") ?: badRequest("missing number param 'from'")
        val to = params.int("to") ?: badRequest("missing number param 'to'")
        val expected = params.long("expectedRevision") ?: badRequest("missing number param 'expectedRevision'")
        backendReady()
        withContext(Dispatchers.Main) {
            val state = DesktopPlayerState.queue.state.value
            if (QueueRevision.observe(state) != expected) throw ApiException("conflict", "queue revision changed")
            if (!QueueIndex.validMove(state.entries.size, from, to)) badRequest("from and to must be queue indices")
            if (from != to) DesktopPlayerState.moveQueueItem(from = from, to = to)
        }
        return emptyResult()
    }

    /** `playQueueOccurrence(key: Long)`: plays that entry and keeps the queue. */
    private suspend fun select(params: JsonObject): JsonElement {
        val qid = params.requireStr("qid")
        backendReady()
        PlayerApi.ensureCoreDefaults()
        withContext(Dispatchers.Main) {
            val state = DesktopPlayerState.queue.state.value
            val index = QueueIndex.indexOfQid(state, qid)
            if (index < 0) throw ApiException("not_found", "no such queue entry")
            DesktopPlayerState.playQueueOccurrence(state.entries[index].key)
        }
        return emptyResult()
    }

    /** Removes everything but the playing entry; `keepCurrent = false` also pauses and removes it. */
    private suspend fun clear(params: JsonObject): JsonElement {
        val keepCurrent = params.bool("keepCurrent") ?: true
        backendReady()
        withContext(Dispatchers.Main) {
            val state = DesktopPlayerState.queue.state.value
            val indices = QueueIndex.clearIndices(state.entries.size, state.currentIndex, keepCurrent)
            if (!keepCurrent) DesktopPlayerState.pause()
            if (indices.isNotEmpty() && DesktopPlayerState.removeQueueItems(indices) == null) refused()
        }
        return emptyResult()
    }

    /**
     * `startRadio(song: SongItem)` replaces the queue with a radio seeded by the song and loads the recommendations
     * asynchronously; the call returns once they are in (or after [RADIO_WAIT_MS]).
     */
    private suspend fun radio(params: JsonObject): JsonElement {
        val seedId = params.requireStr("seedId")
        backendReady()
        PlayerApi.ensureCoreDefaults()
        val seed = PlayerApi.resolve(listOf(seedId)).first()
        val states = withContext(Dispatchers.Main) { DesktopPlayerState.queue.state }
        withContext(Dispatchers.Main) { DesktopPlayerState.startRadio(seed) }
        withTimeoutOrNull(RADIO_WAIT_MS) { states.first { QueueIndex.radioReady(it, seedId, RADIO_MIN_SIZE) } }
        return emptyResult()
    }

    // --- queue topic ---------------------------------------------------------------------------------------------

    /** `queue.snapshot {total, revision, currentIndex, head}`, then `queue.changed {revision, currentIndex}` at most every 250 ms. */
    @OptIn(FlowPreview::class)
    val queueTopic: Topic =
        topic { _, emit ->
            backendReady()
            QueueTracker.ensureStarted()
            val states = withContext(Dispatchers.Main) { DesktopPlayerState.queue.state }
            val first = states.value
            var lastRevision = QueueRevision.observe(first)
            var lastLocked = Settings.shared.isQueueLocked
            emit("queue.snapshot", QueueMapper.snapshot(first, lastRevision, lastLocked))
            coroutineScope {
                // The lock changes without a queue change: tell the UI with the same event.
                launch(Dispatchers.Main) {
                    snapshotFlow { Settings.shared.isQueueLocked }.collect { locked ->
                        if (locked != lastLocked) {
                            lastLocked = locked
                            val state = states.value
                            emit("queue.changed", QueueMapper.changed(state, QueueRevision.observe(state), locked))
                        }
                    }
                }
                states.sample(SAMPLE_MS).collect { state ->
                    val revision = QueueRevision.observe(state)
                    if (revision != lastRevision) {
                        lastRevision = revision
                        emit("queue.changed", QueueMapper.changed(state, revision, Settings.shared.isQueueLocked))
                    }
                }
            }
        }

    private const val SAMPLE_MS = 250L
    private const val RADIO_WAIT_MS = 12_000L
    private const val RADIO_MIN_SIZE = 10
}
