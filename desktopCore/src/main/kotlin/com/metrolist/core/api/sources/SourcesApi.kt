package com.metrolist.core.api.sources

import com.metrolist.core.api.account.AccountBus
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireBool
import com.metrolist.core.server.requireStr
import com.metrolist.core.shell.WebLogin
import com.metrolist.core.shell.WebLoginConfigs
import com.metrolist.desktop.source.DesktopSourceLoginException
import com.metrolist.desktop.source.DesktopSourceLogins
import com.metrolist.desktop.source.DesktopZingCookieStore
import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.BestSourceSelector
import com.metrolist.shared.source.SourceEntitlement
import com.metrolist.shared.source.SourceHealth
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.isSourceEnabled
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.mp.KoinPlatform

/** api.md 3.11 `sources.*`, `source.zing.*`, `source.nct.*`. */
object SourcesApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "sources.list" to handler { _, _ -> listJson() },
            "sources.setEnabled" to
                handler { params, _ ->
                    setEnabled(params.requireStr("id"), params.requireBool("on"))
                },
            "sources.setPriority" to handler { params, _ -> setPriority(params) },
            "sources.setAutoBest" to
                handler { params, _ ->
                    Settings.shared.autoBestSource = params.requireBool("on")
                    AccountBus.sourcesChanged()
                    emptyResult()
                },
            "sources.setAutoFallback" to
                handler { params, _ ->
                    // Stored only: v1 has no routing hook for the fallback.
                    SourcePrefs.autoFallback = params.requireBool("on")
                    AccountBus.sourcesChanged()
                    emptyResult()
                },
            "source.zing.login" to handler { _, _ -> zingLogin() },
            "source.zing.logout" to handler { _, _ -> logout(SourceId.ZING) },
            "source.nct.login" to handler { params, _ -> nctLogin(params) },
            "source.nct.logout" to handler { _, _ -> logout(SourceId.NCT) },
        )

    // --- sources.list --------------------------------------------------------------------------------------------

    /** The `sources.list` result; also the `sources` part of the `account` topic. Never waits for the network. */
    fun listJson(): JsonObject {
        val rows = SourcesMapper.IDS.map(::row)
        return SourcesMapper.list(rows, SourcePrefs.priority, Settings.shared.autoBestSource, SourcePrefs.autoFallback)
    }

    private fun row(id: SourceId): SourceRow {
        val enabled = Settings.shared.isSourceEnabled(id)
        // DesktopSourceLogins.health(source): SourceHealth (HEALTHY when no registry is registered yet)
        val degraded = enabled && DesktopSourceLogins.health(id) == SourceHealth.DEGRADED
        return when (id) {
            SourceId.YT -> {
                val auth = AuthManager()
                val signedIn = auth.isLoggedIn
                SourceRow(id, enabled, degraded, signedIn, null, if (signedIn) auth.accountEmail.ifBlank { auth.accountName } else null)
            }

            SourceId.ZING, SourceId.NCT -> {
                // DesktopSourceLogins.hasSavedSession(source): Boolean (instant, no network)
                val signedIn = DesktopSourceLogins.hasSavedSession(id)
                if (signedIn) SourceAccountCache.refreshIfStale(id) else SourceAccountCache.clear(id)
                val account = if (signedIn) SourceAccountCache.get(id)?.account else null
                // Unknown entitlement counts as FREE: never show VIP (or 320) before the account check confirmed it.
                SourceRow(id, enabled, degraded, signedIn, signedIn && account?.entitlement == SourceEntitlement.VIP, account?.displayName)
            }

            else -> {
                SourceRow(id, enabled, degraded, null, null, null)
            }
        }
    }

    // --- settings ------------------------------------------------------------------------------------------------

    private fun setEnabled(
        idText: String,
        on: Boolean,
    ): JsonElement {
        val id = SourcesMapper.parseId(idText) ?: badRequest("id: unknown source")
        when (id) {
            SourceId.SC -> Settings.shared.soundCloudSourceEnabled = on
            SourceId.ZING -> Settings.shared.zingSourceEnabled = on
            SourceId.NCT -> Settings.shared.nctSourceEnabled = on
            else -> badRequest("id: this source cannot be switched off")
        }
        AccountBus.sourcesChanged()
        return emptyResult()
    }

    private fun setPriority(params: JsonObject): JsonElement {
        val order = (params["order"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: badRequest("order: strings required") }
        if (order == null || !SourcesMapper.isValidPriority(order)) badRequest("order: every source exactly once")
        // Stored only: v1 has no routing hook for a priority order.
        SourcePrefs.priority = order
        AccountBus.sourcesChanged()
        return emptyResult()
    }

    // --- Zing / NCT login ----------------------------------------------------------------------------------------

    private suspend fun zingLogin(): JsonElement {
        backendReady()
        val result = WebLogin.request(WebLoginConfigs.zing())
        if (result.cancelled) throw ApiException("cancelled", "login cancelled")
        val header = result.header()
        if (header == null || !DesktopZingCookieStore.hasSessionCookie(header)) throw ApiException("not_signed_in", "Zing returned no session")
        val store = KoinPlatform.getKoinOrNull()?.getOrNull<DesktopZingCookieStore>() ?: DesktopZingCookieStore()
        // DesktopZingCookieStore.save(cookieHeader: String) (suspend): encrypted (DPAPI) Settings value
        store.save(header)
        // Mirrors DesktopSourceLogins.loginZing (not called: it launches the old helper exe). account(): success(null) = the
        // session is not logged in -> clear; failure = Zing unreachable -> keep the session.
        val check = DesktopSourceLogins.account(SourceId.ZING)
        if (check.isSuccess && check.getOrNull() == null) {
            store.clear()
            SourceAccountCache.clear(SourceId.ZING)
            AccountBus.sourcesChanged()
            throw ApiException("not_signed_in", "Zing did not accept the session")
        }
        val account = check.getOrNull()
        SourceAccountCache.put(SourceId.ZING, account)
        invalidateBestSource()
        AccountBus.sourcesChanged()
        return buildJsonObject {
            put("ok", true)
            put("vip", account?.entitlement == SourceEntitlement.VIP)
        }
    }

    private suspend fun nctLogin(params: JsonObject): JsonElement {
        val account = params.requireStr("account")
        val password = params.requireStr("password")
        // phone and countryCode are not supported by v1's NCT login and are ignored (the M3-3 suite sends them on a good login).
        backendReady()
        // The password is read into this call's argument only: not stored, not logged, not kept. A JVM String cannot be zeroed
        // (the char-array zeroing in api.md is not achievable with v1's String API); the reference dies with this frame.
        val outcome = DesktopSourceLogins.loginNct(username = account, password = password)
        val failure = outcome.exceptionOrNull()
        if (failure != null) throw loginFailure(SourceId.NCT, failure)
        val signedIn = DesktopSourceLogins.account(SourceId.NCT).getOrNull()
        SourceAccountCache.put(SourceId.NCT, signedIn)
        invalidateBestSource()
        AccountBus.sourcesChanged()
        return buildJsonObject {
            put("ok", true)
            put("vip", signedIn?.entitlement == SourceEntitlement.VIP)
        }
    }

    private suspend fun logout(id: SourceId): JsonElement {
        backendReady()
        val failure = DesktopSourceLogins.logout(id).exceptionOrNull()
        SourceAccountCache.clear(id)
        invalidateBestSource()
        AccountBus.sourcesChanged()
        if (failure != null) throw loginFailure(id, failure)
        return emptyResult()
    }

    /** A fixed message: the underlying exception text is never passed on (it must not hold input). */
    private fun loginFailure(
        id: SourceId,
        failure: Throwable,
    ): ApiException {
        val (code, retryable) =
            if (failure is DesktopSourceLoginException) SourcesMapper.loginError(failure.reason) else "internal" to false
        return ApiException(
            code,
            "${SourcesMapper.name(id)} sign-in failed",
            retryable = retryable,
            details = buildJsonObject { put("sourceId", id.key) },
        )
    }

    private fun invalidateBestSource() {
        // BestSourceSelector.invalidateAccounts(): forgets cached entitlements (Koin single of the shared module).
        KoinPlatform.getKoinOrNull()?.getOrNull<BestSourceSelector>()?.invalidateAccounts()
    }
}
