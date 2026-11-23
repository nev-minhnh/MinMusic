package com.metrolist.core.api.queue

import com.metrolist.core.api.jsonOf
import com.metrolist.core.api.toJson
import com.metrolist.core.api.toTrack
import com.metrolist.core.server.ApiException
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.sourceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Where `queue.add` puts songs. */
sealed interface QueuePosition {
    data object Next : QueuePosition

    data object End : QueuePosition

    data class At(
        val index: Int,
    ) : QueuePosition
}

/** Pure mapping of the v1 queue state to api.md `queue.*` shapes. */
object QueueMapper {
    const val MAX_WINDOW = 200
    const val HEAD_SIZE = 50
    const val HEAD_BEFORE = 5

    private fun badPosition(): Nothing = throw ApiException("bad_request", "position must be next, end or a number")

    /** `queue.window` bounds: offset >= 0 and 1 <= limit <= [MAX_WINDOW]; anything else is `bad_request`. */
    fun checkRange(
        offset: Int,
        limit: Int,
    ) {
        if (offset < 0 || limit !in 1..MAX_WINDOW) throw ApiException("bad_request", "offset must be >= 0 and limit 1..$MAX_WINDOW")
    }

    /** The bare item `PlayerApi.resolve` falls back to for a YouTube id it could not find anywhere (no title, no artist, no art). */
    fun isUnresolved(song: SongItem): Boolean =
        sourceOf(song.id) == SourceId.YT && song.title == song.id && song.artists.isEmpty() && song.thumbnail == null

    /** `"next"`, `"end"` or a non-negative number; anything else is `bad_request`. */
    fun parsePosition(value: JsonElement?): QueuePosition {
        val primitive = value as? JsonPrimitive ?: throw ApiException("bad_request", "missing 'position'")
        if (primitive.isString) {
            return when (primitive.content) {
                "next" -> QueuePosition.Next
                "end" -> QueuePosition.End
                else -> QueuePosition.At(primitive.content.toIntOrNull()?.takeIf { it >= 0 } ?: badPosition())
            }
        }
        val index = primitive.content.toDoubleOrNull()?.toInt()?.takeIf { it >= 0 } ?: badPosition()
        return QueuePosition.At(index)
    }

    /** A queue entry as `Track & {qid}`. */
    fun item(entry: MusicQueueEntry): JsonObject =
        JsonObject(entry.song.toTrack().toJson().jsonObject + ("qid" to JsonPrimitive(entry.key.toString())))

    /** `queue.window`: [offset] and [limit] are clamped (limit to 1..200). */
    fun window(
        state: MusicQueueState,
        revision: Long,
        offset: Int,
        limit: Int,
    ): JsonObject {
        val from = offset.coerceIn(0, state.entries.size)
        val to = minOf(state.entries.size, from + limit.coerceIn(1, MAX_WINDOW))
        return jsonOf(
            "total" to JsonPrimitive(state.entries.size),
            "revision" to JsonPrimitive(revision),
            "currentIndex" to JsonPrimitive(state.currentIndex),
            "items" to JsonArray(state.entries.subList(from, to).map { item(it) }),
        )
    }

    /** `queue.snapshot`: `head` holds 50 entries starting 5 before the current one. */
    fun snapshot(
        state: MusicQueueState,
        revision: Long,
    ): JsonObject {
        val from = (state.currentIndex - HEAD_BEFORE).coerceIn(0, state.entries.size)
        val to = minOf(state.entries.size, from + HEAD_SIZE)
        return jsonOf(
            "total" to JsonPrimitive(state.entries.size),
            "revision" to JsonPrimitive(revision),
            "currentIndex" to JsonPrimitive(state.currentIndex),
            "head" to JsonArray(state.entries.subList(from, to).map { item(it) }),
        )
    }

    fun changed(
        state: MusicQueueState,
        revision: Long,
    ): JsonObject = jsonOf("revision" to JsonPrimitive(revision), "currentIndex" to JsonPrimitive(state.currentIndex))

    /** Index the first added song should take: after the playing entry, never past the old end. */
    fun insertIndex(
        requested: Int,
        currentIndexAfter: Int,
        sizeBefore: Int,
    ): Int = maxOf(currentIndexAfter + 1, minOf(requested, sizeBefore))

    /** Keys present in [after] but not in [before], in queue order. */
    fun addedKeys(
        before: MusicQueueState,
        after: MusicQueueState,
    ): List<Long> {
        val known = before.entries.mapTo(HashSet()) { it.key }
        return after.entries.filter { it.key !in known }.map { it.key }
    }

    /** True when the queue is on its last entry (the point where autoplay or stop decides). */
    fun atEnd(state: MusicQueueState): Boolean = state.entries.isNotEmpty() && state.currentIndex == state.entries.lastIndex
}
