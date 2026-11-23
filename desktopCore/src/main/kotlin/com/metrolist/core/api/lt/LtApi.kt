package com.metrolist.core.api.lt

import com.metrolist.core.api.PlayerApi
import com.metrolist.core.api.json
import com.metrolist.core.api.jsonOf
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.notFound
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.topic
import com.metrolist.core.server.unsupported
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.listentogether.network.isAllowedListenTogetherServerUrl
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Input rules of `lt.create` / `lt.join` (pure). The UI uses 8-character codes; the mock accepts 6, so 4..8 letters or digits. */
object LtInput {
    private val CODE = Regex("^[A-Za-z0-9]{4,8}$")
    private const val MAX_NAME = 32

    fun code(raw: String): String? = raw.trim().takeIf { CODE.matches(it) }?.uppercase()

    fun name(raw: String): String? = raw.trim().take(MAX_NAME).trim().takeIf { it.isNotEmpty() }
}

/** api.md 3.13. */
object LtApi {
    private val manager: ListenTogetherManager get() = ListenTogetherState.listenTogetherManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val SAMPLE_MS = 250L

    val handlers: Map<String, Handler> =
        mapOf(
            "lt.state" to handler { _, _ -> stateNow() },
            "lt.create" to handler { params, _ -> create(params.requireStr("name")) },
            "lt.join" to handler { params, _ -> join(params.requireStr("code"), params.requireStr("name")) },
            "lt.leave" to
                handler { _, _ ->
                    backendReady()
                    manager.leaveRoomAndWait() // suspend fun leaveRoomAndWait() (:572)
                    emptyResult()
                },
            "lt.approve" to handler { params, _ -> approve(params.requireStr("userId")) },
            "lt.reject" to handler { params, _ -> reject(params.requireStr("userId")) },
            "lt.kick" to handler { params, _ -> kick(params.requireStr("userId")) },
            "lt.transferHost" to handler { params, _ -> transferHost(params.requireStr("userId")) },
            "lt.suggest" to handler { params, _ -> suggest(params.requireStr("trackId")) },
            "lt.suggestion.approve" to handler { params, _ -> suggestionDecision(params.requireStr("id"), approve = true) },
            "lt.suggestion.reject" to handler { params, _ -> suggestionDecision(params.requireStr("id"), approve = false) },
            "lt.servers" to handler { _, _ -> servers() },
            "lt.setServer" to handler { params, _ -> setServer(params.requireStr("url")) },
            "lt.blocked" to handler { _, _ -> blocked() },
            "lt.unblock" to handler { params, _ -> unblock(params.requireStr("id")) },
            "lt.invite" to handler { _, _ -> invite() },
        )

    // --- state ---------------------------------------------------------------------------------------------------

    private fun snapshot(): LtSnapshot =
        LtSnapshot(
            connection = manager.connectionState.value,
            role = manager.role.value,
            room = manager.roomState.value,
            pending = manager.pendingJoinRequests.value,
            serverUrl = manager.serverUrl.value,
            roomRequestPending = manager.roomRequestPending.value,
            joinRequestPending = manager.joinRequestPending.value,
        )

    private suspend fun stateNow(): JsonElement {
        backendReady()
        return LtStateMapper.map(snapshot())
    }

    /** `lt.snapshot`, then `lt.state` (sampled 250 ms), `lt.joinRequest` and `lt.suggestion` once per new entry. */
    @OptIn(FlowPreview::class)
    val ltTopic: Topic =
        topic { _, emit ->
            backendReady()
            val m = manager
            val diff = LtEventDiff()
            emit("lt.snapshot", LtStateMapper.map(snapshot()))
            val watcher =
                scope.launch {
                    combine(m.pendingJoinRequests, m.pendingSuggestions) { joins, suggestions -> joins to suggestions }.collect { (joins, suggestions) ->
                        for (join in diff.joinRequests(joins)) emit("lt.joinRequest", LtStateMapper.joinRequestEvent(join))
                        for (suggestion in diff.suggestions(suggestions)) emit("lt.suggestion", LtStateMapper.suggestionEvent(suggestion))
                    }
                }
            try {
                val connection = combine(m.connectionState, m.role, m.roomState, m.pendingJoinRequests, m.serverUrl) { c, r, room, p, url -> LtSnapshot(c, r, room, p, url, false, false) }
                val flags = combine(m.roomRequestPending, m.joinRequestPending) { a, b -> a to b }
                combine(connection, flags) { s, (room, join) -> s.copy(roomRequestPending = room, joinRequestPending = join) }
                    .sample(SAMPLE_MS)
                    .collect { emit("lt.state", LtStateMapper.map(it)) }
            } finally {
                watcher.cancel()
            }
        }

    // --- rooms ---------------------------------------------------------------------------------------------------

    private fun refused(): Nothing {
        if (!Settings.shared.listenTogetherEnabled) unsupported("Listen Together is turned off in settings")
        throw ApiException("conflict", "already in a room or a room request is pending")
    }

    private suspend fun create(rawName: String): JsonElement {
        val name = LtInput.name(rawName) ?: badRequest("name must not be blank")
        backendReady()
        manager.setUsername(name) // fun setUsername(value: String) (:731)
        // fun createRoom(overrideUsername: String? = null): Boolean (:493)
        if (!manager.createRoom(overrideUsername = name)) refused()
        return emptyResult()
    }

    private suspend fun join(
        rawCode: String,
        rawName: String,
    ): JsonElement {
        val code = LtInput.code(rawCode) ?: badRequest("code must be 4 to 8 letters or digits")
        val name = LtInput.name(rawName) ?: badRequest("name must not be blank")
        backendReady()
        // fun joinRoom(roomCode: String, overrideUsername: String? = null): Boolean (:510)
        if (!manager.joinRoom(roomCode = code, overrideUsername = name)) refused()
        return emptyResult()
    }

    private fun requireHost(what: String) {
        if (manager.role.value != ListenTogetherRoomRole.HOST) badRequest("only the host can $what")
    }

    private suspend fun approve(userId: String): JsonElement {
        backendReady()
        requireHost("approve join requests")
        if (manager.pendingJoinRequests.value.none { it.userId == userId }) notFound("pending user not found")
        manager.approveJoin(userId) // fun approveJoin(userId: String) (:597)
        return emptyResult()
    }

    private suspend fun reject(userId: String): JsonElement {
        backendReady()
        requireHost("reject join requests")
        if (manager.pendingJoinRequests.value.none { it.userId == userId }) notFound("pending user not found")
        manager.rejectJoin(userId, reason = null) // fun rejectJoin(userId: String, reason: String? = null) (:603)
        return emptyResult()
    }

    private suspend fun kick(userId: String): JsonElement {
        backendReady()
        requireHost("kick users")
        val target = manager.roomState.value?.users?.firstOrNull { it.userId == userId }
        if (target == null || target.isHost) notFound("guest not found")
        manager.kickUser(userId, reason = null) // fun kickUser(userId: String, reason: String? = null) (:612)
        return emptyResult()
    }

    private suspend fun transferHost(userId: String): JsonElement {
        backendReady()
        requireHost("transfer the room")
        val target = manager.roomState.value?.users?.firstOrNull { it.userId == userId }
        if (target == null || target.isHost) notFound("guest not found")
        manager.transferHost(userId) // new in M3-4a: fun transferHost(userId: String)
        return emptyResult()
    }

    private suspend fun suggest(trackId: String): JsonElement {
        backendReady()
        if (manager.role.value != ListenTogetherRoomRole.GUEST) badRequest("only a guest can suggest a track")
        // internal suspend fun resolve(ids: List<String>): List<SongItem> (PlayerApi.kt:217)
        val song = PlayerApi.resolve(listOf(trackId)).firstOrNull() ?: notFound("track not found")
        manager.suggestTrack(song) // fun suggestTrack(song: SongItem) (:642)
        return emptyResult()
    }

    private suspend fun suggestionDecision(
        id: String,
        approve: Boolean,
    ): JsonElement {
        backendReady()
        requireHost("decide on suggestions")
        if (manager.pendingSuggestions.value.none { it.id == id }) notFound("suggestion not found")
        if (approve) {
            manager.approveSuggestion(id) // fun approveSuggestion(suggestionId: String) (:617)
        } else {
            manager.rejectSuggestion(id, reason = null) // fun rejectSuggestion(suggestionId: String, reason: String? = null) (:630)
        }
        return emptyResult()
    }

    // --- servers, blocked, invite --------------------------------------------------------------------------------

    private suspend fun servers(): JsonElement {
        backendReady()
        return buildJsonObject {
            put(
                "items",
                JsonArray(
                    manager.servers.value.map {
                        jsonOf(
                            "name" to it.name.json(),
                            "url" to JsonPrimitive(it.url),
                            "operator" to it.operator.json(),
                            "custom" to JsonPrimitive(it.isCustom),
                        )
                    },
                ),
            )
            put("current", manager.serverUrl.value)
        }
    }

    private suspend fun setServer(rawUrl: String): JsonElement {
        val url = rawUrl.trim()
        if (!isAllowedListenTogetherServerUrl(url)) badRequest("url must be a wss:// server address")
        backendReady()
        if (manager.servers.value.none { it.url == url }) {
            manager.addCustomServer(url) // fun addCustomServer(value: String) (:763); it selects the server too
        } else {
            manager.setServerUrl(url) // fun setServerUrl(value: String) (:736)
        }
        return emptyResult()
    }

    private suspend fun blocked(): JsonElement {
        backendReady()
        return buildJsonObject {
            put(
                "items",
                JsonArray(
                    manager.blockedUsernames.value.sorted().map {
                        buildJsonObject {
                            put("id", it)
                            put("name", it)
                        }
                    },
                ),
            )
        }
    }

    private suspend fun unblock(id: String): JsonElement {
        backendReady()
        if (id !in manager.blockedUsernames.value) notFound("blocked user not found")
        manager.unblockUser(id) // fun unblockUser(username: String) (:824); the id is the username
        return emptyResult()
    }

    private suspend fun invite(): JsonElement {
        backendReady()
        val url = manager.roomLink(useParty = false) ?: badRequest("not in a room") // fun roomLink(useParty: Boolean = false): String? (:829)
        return buildJsonObject { put("url", url) }
    }
}
