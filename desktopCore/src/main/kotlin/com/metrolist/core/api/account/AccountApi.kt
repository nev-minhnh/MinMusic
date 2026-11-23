package com.metrolist.core.api.account

import androidx.compose.runtime.snapshotFlow
import com.metrolist.core.CoreLog
import com.metrolist.core.api.sources.SourceAccountCache
import com.metrolist.core.api.sources.SourcesApi
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.bool
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.core.server.topic
import com.metrolist.core.shell.WebLogin
import com.metrolist.core.shell.WebLoginConfigs
import com.metrolist.core.shell.WebLoginResult
import com.metrolist.core.shell.YoutubeLoginData
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.auth.selectionDataSyncId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** api.md 3.11 `account.*` and the `account` topic. Cookies and tokens go only to AuthManager (the encrypted Settings store). */
object AccountApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "account.get" to handler { params, _ -> get(params.bool("channels") == true) },
            "account.youtube.login" to handler { _, _ -> login() },
            "account.youtube.switchChannel" to handler { _, _ -> switchChannel() },
            "account.youtube.logout" to handler { _, _ -> logout() },
            "account.youtube.tokenLogin" to
                handler { params, _ ->
                    // The cookie is read into a local and handed to AuthManager; it is never logged.
                    tokenLogin(params.requireStr("cookie"), params.str("visitorData").orEmpty(), params.str("dataSyncId").orEmpty())
                },
        )

    /** `account.snapshot {account, sources}`, then `account.changed` / `sources.changed` after a change. */
    val accountTopic: Topic =
        topic { _, emit ->
            backendReady()
            emit(
                "account.snapshot",
                buildJsonObject {
                    put("account", accountJson(false))
                    put("sources", SourcesApi.listJson())
                },
            )
            coroutineScope {
                launch { AccountBus.accountSignals.collect { emit("account.changed", accountJson(false)) } }
                launch { AccountBus.sourcesSignals.collect { emit("sources.changed", SourcesApi.listJson()) } }
                // The avatar arrives after sign-in (AuthState.fetchAccountInfo), so watch it on Main.
                launch(Dispatchers.Main) { snapshotFlow { AuthState.profilePicUrl }.drop(1).collect { AccountBus.accountChanged() } }
            }
        }

    // --- account.get ---------------------------------------------------------------------------------------------

    private suspend fun get(withChannels: Boolean): JsonElement {
        backendReady()
        return accountJson(withChannels)
    }

    private suspend fun accountJson(withChannels: Boolean): JsonObject {
        val auth = AuthManager()
        val signedIn = auth.isLoggedIn
        val channels =
            if (signedIn && withChannels) {
                // AuthManager.fetchAccounts(cookie, visitorData, dataSyncId, authUser): List<AccountsListResponse.Account>
                runCatching { auth.fetchAccounts(auth.innerTubeCookie, auth.visitorData, auth.dataSyncId, auth.authUser) }
                    .getOrNull()
                    ?.map { (it.dataSyncId ?: it.pageId ?: it.name) to it.name }
            } else {
                null
            }
        return AccountMapper.account(signedIn, auth.accountName, auth.accountEmail, auth.accountChannelHandle, auth.accountThumbnail, channels)
    }

    // --- login / logout ------------------------------------------------------------------------------------------

    private suspend fun login(): JsonElement {
        backendReady()
        val result = WebLogin.request(WebLoginConfigs.youtube())
        if (result.cancelled) throw ApiException("cancelled", "login cancelled")
        completeLogin(result, null)
        return buildJsonObject { put("ok", true) }
    }

    /**
     * Validates the web session and stores it. [dataSyncIdOverride] is the account chosen by `switchChannel`; the script
     * result of the login window supplies the rest.
     */
    private suspend fun completeLogin(
        result: WebLoginResult,
        dataSyncIdOverride: String?,
    ) {
        val cookie = result.header() ?: throw ApiException("unauthorized", "no YouTube session cookie")
        val data = YoutubeLoginData.from(result.scriptResult) ?: throw ApiException("unauthorized", "no YouTube session data")
        save(cookie, data.visitorData, dataSyncIdOverride ?: data.dataSyncId, data.authUser)
    }

    private suspend fun save(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ) {
        // AuthManager.validateAndSaveCredentials(cookie, visitorData, dataSyncId, authUser): Boolean (suspend)
        val valid = AuthManager().validateAndSaveCredentials(cookie, visitorData, dataSyncId, authUser)
        if (!valid) throw ApiException("unauthorized", "YouTube did not accept the session")
        // AuthState.updateAuth(cookie, visitorData, dataSyncId, authUser, accountInfo = null, refreshData = true)
        withContext(Dispatchers.Main) { AuthState.updateAuth(cookie, visitorData, dataSyncId, authUser) }
        SourceAccountCache.clear(com.metrolist.shared.source.SourceId.YT)
        AccountBus.accountChanged()
        AccountBus.sourcesChanged()
    }

    private suspend fun switchChannel(): JsonElement {
        backendReady()
        val auth = AuthManager()
        if (!auth.isLoggedIn) throw ApiException("not_signed_in", "not signed in")
        val cookie = auth.innerTubeCookie
        val visitorData = auth.visitorData
        val dataSyncId = auth.dataSyncId
        val authUser = auth.authUser
        val accounts: List<AccountsListResponse.Account> =
            try {
                auth.fetchAccounts(cookie, visitorData, dataSyncId, authUser)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw ApiException("network", "could not list the YouTube channels", retryable = true)
            }
        val index = AccountMapper.nextChannelIndex(accounts.map { it.isSelected }) ?: throw ApiException("bad_request", "no other channel to switch to")
        val target = accounts[index]
        // Copy of v1's AuthData.forAccount / selectWebViewAccount (desktopApp LoginDialog.kt:811-840, internal there):
        // an entry with a data sync id is selected directly; otherwise its signin URL is opened in the login window.
        val chosen = target.selectionDataSyncId(dataSyncId)
        if (chosen != null) {
            save(cookie, visitorData, chosen, authUser)
        } else {
            val url = AccountMapper.absoluteSigninUrl(target.signinUrl) ?: throw ApiException("unsupported", "this channel cannot be selected")
            val result = WebLogin.request(WebLoginConfigs.youtube(url))
            if (result.cancelled) throw ApiException("cancelled", "login cancelled")
            completeLogin(result, null)
        }
        return emptyResult()
    }

    private suspend fun logout(): JsonElement {
        backendReady()
        // AuthState.signOut(): Throwable? (suspend). It clears the credentials itself (AuthManager.clearAllCredentials).
        val failure = withContext(Dispatchers.Main) { AuthState.signOut() }
        if (failure != null) CoreLog.error("YouTube sign-out cleanup failed (${failure::class.java.simpleName})")
        SourceAccountCache.clear(com.metrolist.shared.source.SourceId.YT)
        AccountBus.accountChanged()
        AccountBus.sourcesChanged()
        return emptyResult()
    }

    private suspend fun tokenLogin(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
    ): JsonElement {
        backendReady()
        save(cookie, visitorData, dataSyncId, "0")
        return emptyResult()
    }
}
