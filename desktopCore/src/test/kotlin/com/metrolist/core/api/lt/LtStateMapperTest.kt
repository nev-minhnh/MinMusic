package com.metrolist.core.api.lt

import com.metrolist.shared.listentogether.ListenTogetherConnectionState
import com.metrolist.shared.listentogether.ListenTogetherConnectionState.CONNECTED
import com.metrolist.shared.listentogether.ListenTogetherConnectionState.CONNECTING
import com.metrolist.shared.listentogether.ListenTogetherConnectionState.DISCONNECTED
import com.metrolist.shared.listentogether.ListenTogetherConnectionState.ERROR
import com.metrolist.shared.listentogether.ListenTogetherConnectionState.RECONNECTING
import com.metrolist.shared.listentogether.ListenTogetherJoinRequest
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.listentogether.ListenTogetherRoomRole.GUEST
import com.metrolist.shared.listentogether.ListenTogetherRoomRole.HOST
import com.metrolist.shared.listentogether.ListenTogetherRoomRole.NONE
import com.metrolist.shared.listentogether.ListenTogetherSuggestion
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.listentogether.network.UserInfo
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LtStateMapperTest {
    private fun snap(
        connection: ListenTogetherConnectionState = DISCONNECTED,
        role: ListenTogetherRoomRole = NONE,
        room: RoomState? = null,
        pending: List<ListenTogetherJoinRequest> = emptyList(),
        roomRequestPending: Boolean = false,
        joinRequestPending: Boolean = false,
    ) = LtSnapshot(connection, role, room, pending, "wss://lt.example/ws", roomRequestPending, joinRequestPending)

    private val room =
        RoomState(
            roomCode = "K7QX2M",
            hostId = "u1",
            users = listOf(UserInfo("u1", "Minh", true), UserInfo("u2", "Lan", false)),
            isPlaying = false,
            position = 0,
            lastUpdate = 0,
        )

    @Test
    fun statusTable() {
        assertEquals("host", LtStateMapper.status(snap(CONNECTED, HOST, room)))
        assertEquals("guest", LtStateMapper.status(snap(CONNECTED, GUEST, room)))
        assertEquals("connecting", LtStateMapper.status(snap(CONNECTING)))
        assertEquals("connecting", LtStateMapper.status(snap(RECONNECTING)))
        assertEquals("connecting", LtStateMapper.status(snap(CONNECTED, roomRequestPending = true)))
        assertEquals("connecting", LtStateMapper.status(snap(CONNECTED, joinRequestPending = true)))
        assertEquals("idle", LtStateMapper.status(snap(DISCONNECTED)))
        assertEquals("idle", LtStateMapper.status(snap(CONNECTED)))
        assertEquals("idle", LtStateMapper.status(snap(ERROR)))
        // A role wins over a transient reconnect: the room is still ours.
        assertEquals("host", LtStateMapper.status(snap(RECONNECTING, HOST, room)))
    }

    @Test
    fun idleShapeHasNoRoomAndTheServer() {
        val json = LtStateMapper.map(snap()).jsonObject
        assertEquals("idle", json["status"]!!.jsonPrimitive.content)
        assertNull(json["room"])
        assertNull(json["error"])
        assertEquals("wss://lt.example/ws", json["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorOnlyWhenTheConnectionFailed() {
        assertEquals("connection_failed", LtStateMapper.map(snap(ERROR)).jsonObject["error"]!!.jsonPrimitive.content)
        assertNull(LtStateMapper.map(snap(DISCONNECTED)).jsonObject["error"])
    }

    @Test
    fun roomCarriesUsersAndPending() {
        val json = LtStateMapper.map(snap(CONNECTED, HOST, room, pending = listOf(ListenTogetherJoinRequest("u9", "Hung")))).jsonObject
        val r = json["room"]!!.jsonObject
        assertEquals("K7QX2M", r["code"]!!.jsonPrimitive.content)
        val users = r["users"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("u1" to true, "u2" to false), users.map { it["id"]!!.jsonPrimitive.content to (it["host"]!!.jsonPrimitive.content == "true") })
        assertEquals(listOf("Minh", "Lan"), users.map { it["name"]!!.jsonPrimitive.content })
        val pending = r["pending"]!!.jsonArray.single().jsonObject
        assertEquals("u9", pending["id"]!!.jsonPrimitive.content)
        assertEquals("Hung", pending["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun suggestionTrackIsMappedFromTrackInfo() {
        val info = TrackInfo(id = "dQw4w9WgXcQ", title = "Song", artist = "Artist", album = "Album", duration = 213_000, thumbnail = null)
        val track = LtStateMapper.track(info)
        assertEquals("dQw4w9WgXcQ", track.id)
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artists.single().name)
        assertEquals("Album", track.album?.title)
        assertEquals(213_000L, track.durationMs)

        val event = LtStateMapper.suggestionEvent(ListenTogetherSuggestion("sg1", "u2", "Lan", info)).jsonObject
        assertEquals(setOf("id", "trackId", "by", "track"), event.keys)
        assertEquals("Lan", event["by"]!!.jsonPrimitive.content)
        assertEquals("dQw4w9WgXcQ", event["trackId"]!!.jsonPrimitive.content)
    }
}
