package com.metrolist.desktop.source

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.nct.NctTokenStore
import com.metrolist.shared.source.zing.CredentialProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cookie header of the user's own zingmp3.vn login. It lives in a secure setting, so on Windows it is encrypted
 * with DPAPI (WindowsEncryptedSecretStore), left out of exports and backups, and never logged.
 */
class DesktopZingCookieStore(
    private val settings: Settings = Settings.shared,
) : CredentialProvider {
    override suspend fun cookieHeader(): String? = settings.zingSourceCookie.takeIf(String::isNotBlank)

    val hasSession: Boolean
        get() = settings.zingSourceCookie.isNotBlank()

    suspend fun save(cookieHeader: String) {
        val header = cookieHeader.trim()
        require(header.isNotEmpty()) { "Empty Zing cookie header" }
        withContext(Dispatchers.IO) { settings.zingSourceCookie = header }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { settings.zingSourceCookie = "" }
    }

    override fun toString(): String = "DesktopZingCookieStore(hasSession=$hasSession)"

    companion object {
        /**
         * Cookies that zingmp3.vn sets only for a logged-in session (same check as Android's ZingCookieStore and the
         * WebView2 helper's "zing" provider). A login is still confirmed through the profile endpoint afterwards.
         */
        val SESSION_COOKIE_NAMES = setOf("zmp3_sid", "zpsid")

        fun hasSessionCookie(cookieHeader: String): Boolean =
            cookieHeader.split(';').any { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                name in SESSION_COOKIE_NAMES && value.isNotEmpty()
            }
    }
}

/**
 * The NhacCuaTui session token (never the password). It lives in a secure setting, so on Windows it is encrypted with
 * DPAPI, left out of exports and backups, and never logged.
 */
class DesktopNctTokenStore(
    private val settings: Settings = Settings.shared,
) : NctTokenStore {
    override suspend fun load(): String? = settings.nctSourceToken.takeIf(String::isNotBlank)

    override suspend fun save(token: String?) {
        withContext(Dispatchers.IO) { settings.nctSourceToken = token?.trim().orEmpty() }
    }

    val hasSession: Boolean
        get() = settings.nctSourceToken.isNotBlank()

    suspend fun clear() = save(null)

    override fun toString(): String = "DesktopNctTokenStore(hasSession=$hasSession)"
}
