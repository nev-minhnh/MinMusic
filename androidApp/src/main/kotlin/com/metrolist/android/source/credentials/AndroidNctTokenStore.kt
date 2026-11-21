package com.metrolist.android.source.credentials

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.nct.NctTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The NhacCuaTui session token (never the password). It lives in a secure setting, so it is encrypted with an
 * Android Keystore key, left out of exports and backups, and never logged.
 */
class AndroidNctTokenStore(
    private val settings: Settings = Settings.shared,
) : NctTokenStore {
    override suspend fun load(): String? = settings.nctSourceToken.takeIf(String::isNotBlank)

    override suspend fun save(token: String?) {
        withContext(Dispatchers.IO) { settings.nctSourceToken = token?.trim().orEmpty() }
    }

    val hasSession: Boolean
        get() = settings.nctSourceToken.isNotBlank()

    suspend fun clear() = save(null)

    override fun toString(): String = "AndroidNctTokenStore(hasSession=$hasSession)"
}
