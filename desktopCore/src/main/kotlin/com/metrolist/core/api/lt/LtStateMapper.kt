package com.metrolist.core.api.lt

import com.metrolist.core.api.Track
import com.metrolist.core.api.toJson
import com.metrolist.core.api.toTrack
import com.metrolist.shared.listentogether.ListenTogetherConnectionState
import com.metrolist.shared.listentogether.ListenTogetherJoinRequest
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.listentogether.ListenTogetherSuggestion
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.listentogether.toSongItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The manager flows `lt.state` is built from, as plain values. */
data class LtSnapshot(
    val connection: ListenTogetherConnectionState,
    val role: ListenTogetherRoomRole,
    val room: RoomState?,
    val pending: List<ListenTogetherJoinRequest>,
    val serverUrl: String,
    val roomRequestPending: Boolean,
    val joinRequestPending: Boolean,
)

/** Pure mapping to api.md 3.13 `lt.state`. */
object LtStateMapper {
    fun status(s: LtSnapshot): String =
        when {
            s.role == ListenTogetherRoomRole.HOST -> "host"
            s.role == ListenTogetherRoomRole.GUEST -> "guest"
            s.connection == ListenTogetherConnectionState.CONNECTING ||
                s.connection == ListenTogetherConnectionState.RECONNECTING ||
                s.roomRequestPending ||
                s.joinRequestPending -> "connecting"
            else -> "idle"
        }

    fun map(s: LtSnapshot): JsonElement =
        buildJsonObject {
            put("status", status(s))
            s.room?.let { room ->
                put(
                    "room",
                    buildJsonObject {
                        put("code", room.roomCode)
                        put(
                            "users",
                            JsonArray(
                                room.users.map {
                                    buildJsonObject {
                                        put("id", it.userId)
                                        put("name", it.username)
                                        put("host", it.isHost)
                                    }
                                },
                            ),
                        )
                        put(
                            "pending",
                            JsonArray(
                                s.pending.map {
                                    buildJsonObject {
                                        put("id", it.userId)
                                        put("name", it.username)
                                    }
                                },
                            ),
                        )
                    },
                )
            }
            put("server", s.serverUrl)
            if (s.connection == ListenTogetherConnectionState.ERROR) put("error", "connection_failed")
        }

    /** The suggested track as an api.md `Track` (thumbnail goes through the same allow-list as the v1 UI). */
    fun track(info: TrackInfo): Track = info.toSongItem().toTrack()

    fun joinRequestEvent(request: ListenTogetherJoinRequest): JsonElement =
        buildJsonObject {
            put("userId", request.userId)
            put("name", request.username)
        }

    fun suggestionEvent(suggestion: ListenTogetherSuggestion): JsonElement =
        buildJsonObject {
            put("id", suggestion.id)
            put("trackId", suggestion.trackInfo.id)
            put("by", suggestion.fromUsername)
            put("track", track(suggestion.trackInfo).toJson())
        }
}

/** Emits each join request and suggestion once per subscription (also those already pending when it starts). */
class LtEventDiff {
    private val seenJoin = HashSet<String>()
    private val seenSuggestion = HashSet<String>()

    fun joinRequests(pending: List<ListenTogetherJoinRequest>): List<ListenTogetherJoinRequest> {
        val fresh = pending.filter { seenJoin.add(it.userId) }
        seenJoin.retainAll(pending.mapTo(HashSet()) { it.userId })
        return fresh
    }

    fun suggestions(pending: List<ListenTogetherSuggestion>): List<ListenTogetherSuggestion> {
        val fresh = pending.filter { seenSuggestion.add(it.id) }
        seenSuggestion.retainAll(pending.mapTo(HashSet()) { it.id })
        return fresh
    }
}
