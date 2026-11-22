package com.metrolist.core.api.browse

import com.metrolist.core.api.jsonOf
import com.metrolist.core.api.toJson
import com.metrolist.core.api.toTrack
import com.metrolist.shared.model.SongItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Pure mapping of chart songs to api.md `Track & {rank, trend}`. */
object ChartMapper {
    /** Songs deduplicated by id in order; `rank` is the 1-based position and `trend` is always "same" (no history yet). */
    fun items(songs: List<SongItem>): JsonArray =
        JsonArray(
            songs.distinctBy { it.id }.mapIndexed { index, song ->
                JsonObject(
                    song.toTrack().toJson().jsonObject +
                        jsonOf("rank" to JsonPrimitive(index + 1), "trend" to JsonPrimitive("same")),
                )
            },
        )
}
