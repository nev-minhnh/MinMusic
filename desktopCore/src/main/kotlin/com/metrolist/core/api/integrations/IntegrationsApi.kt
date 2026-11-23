package com.metrolist.core.api.integrations

import com.metrolist.core.api.account.AccountBus
import com.metrolist.core.api.jsonOf
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.core.shell.WebLogin
import com.metrolist.core.shell.WebLoginConfigs
import com.metrolist.desktop.auth.parseDiscordLoginCallback
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.listenbrainz.ListenBrainz
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.scrobble.PendingScrobbleStore
import com.metrolist.shared.scrobble.ScrobbleProvider
import com.metrolist.shared.scrobble.ScrobbleSessions
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * api.md 3.12 Last.fm, ListenBrainz and Discord. Passwords and tokens are used for the one login call only: they are never
 * stored by this code and never logged (the session key / token goes into the shared encrypted Settings through
 * ScrobbleSessions, as in v1).
 */
object IntegrationsApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "lastfm.login" to handler { params, _ -> lastFmLogin(params.requireStr("username"), params.requireStr("password")) },
            "lastfm.logout" to
                handler { _, _ ->
                    backendReady()
                    withContext(Dispatchers.Main) { ScrobbleSessions.disconnect(ScrobbleProvider.LAST_FM) }
                    emptyResult()
                },
            "lastfm.status" to handler { _, _ -> lastFmStatus() },
            "listenbrainz.setToken" to handler { params, _ -> listenBrainzSetToken(params["token"]) },
            "listenbrainz.status" to
                handler { _, _ ->
                    backendReady()
                    jsonOf("user" to Settings.shared.listenBrainzUsername.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) })
                },
            "discord.login" to handler { _, _ -> discordLogin() },
            "discord.logout" to
                handler { _, _ ->
                    backendReady()
                    withContext(Dispatchers.Main) { DiscordRpcManager.logout() }
                    emptyResult()
                },
            "discord.status" to handler { _, _ -> discordStatus() },
        )

    // --- Last.fm -------------------------------------------------------------------------------------------------

    private suspend fun lastFmLogin(
        username: String,
        password: String,
    ): JsonElement {
        backendReady()
        // LastFM.getMobileSession(httpClient: HttpClient, username: String, password: String): Result<LastFmAuthResponse>
        val result = LastFM.getMobileSession(GlobalYouTube.client, username, password)
        result.exceptionOrNull()?.let { error ->
            val (code, retryable) = LastFmErrors.lastFm(error)
            throw ApiException(code, "Last.fm sign-in failed", retryable = retryable)
        }
        val session = result.getOrNull()?.session
        if (session == null || session.key.isBlank()) throw ApiException("unauthorized", "Last.fm returned no session")
        val name = session.name.ifBlank { username }
        // ScrobbleSessions.connectLastFm(account: String, credential: String) (suspend; writes Compose state, so on Main)
        withContext(Dispatchers.Main) { ScrobbleSessions.connectLastFm(account = name, credential = session.key) }
        AccountBus.sourcesChanged()
        return buildJsonObject {
            put("ok", true)
            put("user", name)
        }
    }

    private suspend fun lastFmStatus(): JsonElement {
        backendReady()
        val user = ScrobbleSessions.current(ScrobbleProvider.LAST_FM)?.account
        // PendingScrobbleStore.all(): List<PendingScrobble> (suspend; needs the database)
        val pending = runCatching { PendingScrobbleStore.all().count { it.provider == ScrobbleProvider.LAST_FM } }.getOrDefault(0)
        return jsonOf(
            "user" to user?.let { JsonPrimitive(it) },
            "expired" to JsonPrimitive(Settings.shared.lastFmSessionExpired),
            "scrobbling" to JsonPrimitive(user != null && Settings.shared.lastFmEnabled),
            "nowPlaying" to JsonPrimitive(user != null && Settings.shared.lastFmUseNowPlaying),
            "pending" to JsonPrimitive(pending),
        )
    }

    // --- ListenBrainz --------------------------------------------------------------------------------------------

    private suspend fun listenBrainzSetToken(token: JsonElement?): JsonElement {
        backendReady()
        if (token == null || token is JsonNull) {
            withContext(Dispatchers.Main) { ScrobbleSessions.disconnect(ScrobbleProvider.LISTEN_BRAINZ) }
            return emptyResult()
        }
        val text = (token as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (text.isNullOrEmpty()) throw ApiException("bad_request", "token: string or null required")
        // ListenBrainz.validateToken(httpClient: HttpClient, token: String): Result<ListenBrainzUser(username)>
        val result = ListenBrainz.validateToken(GlobalYouTube.client, text)
        result.exceptionOrNull()?.let { error ->
            val (code, retryable) = LastFmErrors.listenBrainz(error)
            throw ApiException(code, "ListenBrainz token check failed", retryable = retryable)
        }
        val user = result.getOrNull() ?: throw ApiException("unauthorized", "invalid token")
        // ScrobbleSessions.connectListenBrainz(account: String, credential: String) (suspend)
        withContext(Dispatchers.Main) { ScrobbleSessions.connectListenBrainz(account = user.username, credential = text) }
        return emptyResult()
    }

    // --- Discord -------------------------------------------------------------------------------------------------

    private suspend fun discordLogin(): JsonElement {
        backendReady()
        // DiscordRpcManager.init(httpClient: HttpClient) is idempotent (returns when already initialised)
        DiscordRpcManager.init(GlobalYouTube.client)
        // DiscordRpcManager.auth.createSession(): DiscordAuthSession(authorizeUrl, redirectUri, pkceVerifier, state)
        val session = DiscordRpcManager.auth.createSession()
        val result = WebLogin.request(WebLoginConfigs.discord(session.authorizeUrl))
        if (result.cancelled) throw ApiException("cancelled", "login cancelled")
        // Mirrors DesktopDiscordLoginFlow.handleCallback: the callback URL must match the redirect, carry no error and
        // the state of this session.
        val callback = result.finalUrl?.let { parseDiscordLoginCallback(it, session.redirectUri) }
        if (callback == null) throw ApiException("unauthorized", "Discord returned no callback")
        if (callback.error != null) throw ApiException("unauthorized", "Discord login was denied")
        if (callback.state != session.state) throw ApiException("unauthorized", "Discord state mismatch")
        val code = callback.code
        if (code.isNullOrBlank()) throw ApiException("unauthorized", "Discord returned no code")
        val tokens =
            try {
                // DiscordAuth.exchangeCode(code, verifier, state, expectedState): DiscordAuthResult(accessToken, refreshToken, expiresInSec, scope)
                withContext(Dispatchers.IO) { DiscordRpcManager.auth.exchangeCode(code, session.pkceVerifier, session.state, session.state) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw ApiException("unauthorized", "Discord token exchange failed")
            }
        // DiscordRpcManager.storeAndConnect(accessToken: String, refreshToken: String, expiresInSec: Long): tokens go to DiscordTokenStore
        withContext(Dispatchers.Main) { DiscordRpcManager.storeAndConnect(tokens.accessToken, tokens.refreshToken, tokens.expiresInSec) }
        return emptyResult()
    }

    private suspend fun discordStatus(): JsonElement {
        backendReady()
        // DiscordRpcManager.currentUser: StateFlow<DiscordUser?> (id, username, name, avatar)
        val user = DiscordRpcManager.currentUser.value
        return jsonOf(
            "user" to user?.let { JsonPrimitive(it.name.ifBlank { it.username }) },
            "enabled" to JsonPrimitive(Settings.shared.enableDiscordIntegration),
        )
    }
}
