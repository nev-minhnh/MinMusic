package com.metrolist.core.api.queue

import com.metrolist.core.api.PlayerApi
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.int
import com.metrolist.core.server.obj
import com.metrolist.core.server.requireBool
import com.metrolist.core.server.topic
import com.metrolist.desktop.state.playback.PlayerState as DesktopPlayerState
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * api.md 3.6 (`queue.window`, `queue.add`, `queue.addContext`, `queue.setAutoplay`) and the `queue` topic. Every call
 * into the desktop PlayerState runs on core-main. Out of scope here (M2-1): remove, move, select, clear, radio.
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
        return QueueMapper.window(state, QueueRevision.observe(state), offset, limit)
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

    /** Moves the songs just appended to the end to [requested]: each one goes to the key of the entry now at its target slot. Core-main only. */
    private fun moveAdded(
        before: MusicQueueState,
        requested: Int,
    ) {
        val player = DesktopPlayerState
        val after = player.queue.state.value
        val base = QueueMapper.insertIndex(requested, after.currentIndex, before.entries.size)
        QueueMapper.addedKeys(before, after).forEachIndexed { offset, key ->
            val target = player.queue.state.value.entries.getOrNull(base + offset)?.key ?: return@forEachIndexed
            // moveQueueItem(fromKey: Long, toKey: Long, selectedEntryKeys = emptySet()): moves the entry to the slot of toKey.
            if (target != key) player.moveQueueItem(fromKey = key, toKey = target)
        }
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
            emit("queue.snapshot", QueueMapper.snapshot(first, lastRevision))
            states.sample(SAMPLE_MS).collect { state ->
                val revision = QueueRevision.observe(state)
                if (revision != lastRevision) {
                    lastRevision = revision
                    emit("queue.changed", QueueMapper.changed(state, revision))
                }
            }
        }

    private const val SAMPLE_MS = 250L
}
