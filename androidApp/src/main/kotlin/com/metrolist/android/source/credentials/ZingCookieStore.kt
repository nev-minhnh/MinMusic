package com.metrolist.android.source.credentials

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.zing.CredentialProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cookie header of the user's own zingmp3.vn login. It lives in a secure setting, so it is encrypted with an
 * Android Keystore key, left out of exports and backups, and never logged.
 */
class ZingCookieStore(
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

    override fun toString(): String = "ZingCookieStore(hasSession=$hasSession)"

    companion object {
        /**
         * Cookies that zingmp3.vn sets only for a logged-in session. The visitor cookies (zmp3_rqid, zmp3_app_version)
         * never match, so they cannot close the login window early. The names come from the open-source Zing clients
         * and are not documented by Zing; a login is still confirmed through the profile endpoint afterwards.
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
