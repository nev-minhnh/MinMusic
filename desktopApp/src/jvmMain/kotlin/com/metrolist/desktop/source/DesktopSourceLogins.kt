package com.metrolist.desktop.source

import com.metrolist.desktop.auth.readWindowsLoginHelperOutput
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.mm_desk_open_in_browser
import com.metrolist.shared.generated.resources.mm_desk_zing_read_only
import com.metrolist.shared.generated.resources.mm_desk_zing_waiting
import com.metrolist.shared.generated.resources.mm_desk_zing_window_title
import com.metrolist.shared.generated.resources.source_zing_login_privacy
import com.metrolist.shared.source.SourceAccount
import com.metrolist.shared.source.SourceEntitlement
import com.metrolist.shared.source.SourceHealth
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import com.metrolist.shared.source.nct.NctClient
import com.metrolist.shared.source.nct.NctEntitlement
import com.metrolist.shared.source.nct.NctException
import com.metrolist.shared.source.zing.ZingClient
import com.metrolist.shared.source.zing.ZingEntitlement
import com.metrolist.shared.utils.OsUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform
import java.io.File

/**
 * Desktop sign-in, sign-out and account checks for the sources that support the user's own account (Zing MP3, NhacCuaTui).
 * UI (Settings > Nguon nhac page, login dialogs) calls only this object. Every call is main-safe (suspend, IO inside).
 *
 * Public API:
 * - `suspend fun loginZing(): Result<Unit>`
 *     Opens the WebView2 helper (`metro-wv2-helper.exe --provider zing`) on zingmp3.vn. The user signs in there; the helper
 *     returns the zingmp3.vn cookie header once a session cookie appears, it is stored encrypted (DPAPI) and confirmed
 *     through Zing's profile endpoint. Blocks until the window closes. Windows only ([isZingLoginSupported]).
 * - `suspend fun loginNct(username: String, password: String): Result<Unit>`
 *     Sends the password once to graph.nct.vn over HTTPS; only the returned token is stored (encrypted). The password is
 *     never stored or logged. Clear the password field right after calling.
 * - `suspend fun logout(source: SourceId): Result<Unit>` for [SourceId.ZING] or [SourceId.NCT].
 * - `fun hasSavedSession(source: SourceId): Boolean` instant, no network: a cookie/token is stored.
 * - `suspend fun account(source: SourceId): Result<SourceAccount?>` network check: success(null) = signed out,
 *     success(account) = signed in ([SourceAccount.displayName], [SourceAccount.entitlement] FREE/VIP), failure = Zing/NCT
 *     could not be reached (keep showing the saved session).
 * - `fun health(source: SourceId): SourceHealth` HEALTHY/DEGRADED from the shared SourceRegistry kill switch.
 * - `val isZingLoginSupported: Boolean` true on Windows when the helper executable is present.
 *
 * Failures are [DesktopSourceLoginException] with a [DesktopSourceLoginException.Reason] that maps to existing strings:
 * NOT_SIGNED_IN -> source_zing_login_failed, AUTH_FAILED -> source_nct_login_failed, NETWORK ->
 * source_nct_login_network_error, CANCELLED (window closed) -> no message, others -> source_nct_login_error.
 * Source enable toggles stay in Settings (soundCloudSourceEnabled, zingSourceEnabled, nctSourceEnabled, autoBestSource).
 */
object DesktopSourceLogins {
    val isZingLoginSupported: Boolean
        get() = OsUtil.isWindows && resolveWindowsLoginHelper().isFile

    suspend fun loginZing(): Result<Unit> =
        runLogin {
            if (!OsUtil.isWindows) throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.UNSUPPORTED)
            val helper = resolveWindowsLoginHelper()
            if (!helper.isFile) throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.HELPER_MISSING)
            val command = listOf(helper.absolutePath, "--provider", "zing") + zingWindowTextArgs()
            val content = readWindowsLoginHelperOutput(ProcessBuilder(command))
            if (content.isEmpty()) throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.CANCELLED)
            val header =
                runCatching {
                    Json
                        .parseToJsonElement(content)
                        .jsonObject["zingCookie"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()
            if (header.isNullOrBlank() || !DesktopZingCookieStore.hasSessionCookie(header)) {
                throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.NOT_SIGNED_IN)
            }
            zingCookies.save(header)
            // An unreachable Zing keeps the session (Settings checks again later); a definite "logged out" drops it.
            val account =
                try {
                    zingClient.account()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (account != null && !account.loggedIn) {
                zingCookies.clear()
                throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.NOT_SIGNED_IN)
            }
        }

    suspend fun loginNct(
        username: String,
        password: String,
    ): Result<Unit> =
        runLogin {
            if (username.isBlank() || password.isEmpty()) throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.AUTH_FAILED)
            try {
                nctClient.loginWithPassword(username.trim(), password)
            } catch (e: NctException.AuthFailed) {
                throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.AUTH_FAILED, e)
            } catch (e: NctException.Network) {
                throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.NETWORK, e)
            }
        }

    suspend fun logout(source: SourceId): Result<Unit> =
        runLogin {
            when (source) {
                SourceId.ZING -> zingCookies.clear()
                SourceId.NCT -> {
                    nctClient.logout()
                    nctTokens.clear()
                }
                else -> throw DesktopSourceLoginException(DesktopSourceLoginException.Reason.UNSUPPORTED)
            }
        }

    fun hasSavedSession(source: SourceId): Boolean =
        when (source) {
            SourceId.ZING -> zingCookies.hasSession
            SourceId.NCT -> nctTokens.hasSession
            else -> false
        }

    suspend fun account(source: SourceId): Result<SourceAccount?> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(
                    when (source) {
                        SourceId.ZING ->
                            if (!zingCookies.hasSession) {
                                null
                            } else {
                                zingClient.account().takeIf { it.loggedIn }?.let {
                                    SourceAccount(
                                        source = SourceId.ZING,
                                        displayName = it.displayName,
                                        entitlement = entitlement(it.entitlement == ZingEntitlement.VIP),
                                    )
                                }
                            }
                        SourceId.NCT ->
                            if (!nctTokens.hasSession) {
                                null
                            } else {
                                nctClient.account().takeIf { it.loggedIn }?.let {
                                    SourceAccount(
                                        source = SourceId.NCT,
                                        displayName = it.displayName,
                                        entitlement = entitlement(it.entitlement == NctEntitlement.VIP),
                                    )
                                }
                            }
                        else -> null
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun health(source: SourceId): SourceHealth = registry?.health(source) ?: SourceHealth.HEALTHY

    private fun entitlement(vip: Boolean): SourceEntitlement = if (vip) SourceEntitlement.VIP else SourceEntitlement.FREE

    /**
     * Localized text for the Zing login window chrome (desktop layout spec 6): title, read-only address bar label, privacy
     * banner and footer. Display text only; an older helper ignores these flags. Falls back to the helper's English text.
     */
    private suspend fun zingWindowTextArgs(): List<String> =
        try {
            listOf(
                "--title",
                getString(Res.string.mm_desk_zing_window_title),
                "--read-only-label",
                getString(Res.string.mm_desk_zing_read_only),
                "--banner",
                getString(Res.string.source_zing_login_privacy),
                "--status",
                getString(Res.string.mm_desk_zing_waiting),
                "--open-browser-label",
                getString(Res.string.mm_desk_open_in_browser),
                "--cancel-label",
                getString(Res.string.cancel),
            ).map { it.replace("\"", "'") }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    // Prefer the instances the app registered so cookie, key and token caches are shared with playback.
    private val registry: SourceRegistry?
        get() = KoinPlatform.getKoinOrNull()?.getOrNull<SourceRegistry>()

    private val zingCookies: DesktopZingCookieStore by lazy {
        KoinPlatform.getKoinOrNull()?.getOrNull<DesktopZingCookieStore>() ?: DesktopZingCookieStore()
    }
    private val nctTokens: DesktopNctTokenStore by lazy {
        KoinPlatform.getKoinOrNull()?.getOrNull<DesktopNctTokenStore>() ?: DesktopNctTokenStore()
    }
    private val zingClient: ZingClient by lazy {
        KoinPlatform.getKoinOrNull()?.getOrNull<ZingClient>() ?: ZingClient.create(credentials = zingCookies)
    }
    private val nctClient: NctClient by lazy {
        KoinPlatform.getKoinOrNull()?.getOrNull<NctClient>() ?: NctClient(nctTokens)
    }

    private suspend fun runLogin(block: suspend () -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                block()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DesktopSourceLoginException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(DesktopSourceLoginException(DesktopSourceLoginException.Reason.UNKNOWN, e))
            }
        }

    private fun resolveWindowsLoginHelper(): File {
        val helperName = "metro-wv2-helper.exe"
        val resourcesDirectory = System.getProperty("compose.application.resources.dir")?.let(::File)
        return resourcesDirectory
            ?.let { directory ->
                File(directory, "windows/$helperName").takeIf(File::exists) ?: File(directory, helperName)
            } ?: File("desktopApp/src/desktopMain/resources/windows/$helperName").takeIf(File::exists)
            ?: File("src/desktopMain/resources/windows/$helperName")
    }
}

/** Why a source login failed. Messages never contain cookies, tokens, usernames or passwords. */
class DesktopSourceLoginException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception("Source login failed: ${reason.name.lowercase()}", cause) {
    enum class Reason {
        /** Not Windows (Zing login), or a source without accounts. */
        UNSUPPORTED,

        /** metro-wv2-helper.exe is not bundled. */
        HELPER_MISSING,

        /** The login window was closed before a session appeared. */
        CANCELLED,

        /** Zing returned no valid session for the cookies. */
        NOT_SIGNED_IN,

        /** NCT rejected the username or password. */
        AUTH_FAILED,

        /** The source could not be reached. */
        NETWORK,
        UNKNOWN,
    }
}
