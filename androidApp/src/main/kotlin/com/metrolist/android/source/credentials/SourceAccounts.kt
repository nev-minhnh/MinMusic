package com.metrolist.android.source.credentials

import android.webkit.CookieManager
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.source.nct.NctAccount
import com.metrolist.shared.source.nct.NctClient
import com.metrolist.shared.source.nct.NctToken
import com.metrolist.shared.source.zing.ZingAccount
import com.metrolist.shared.source.zing.ZingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

/** Sign-in, sign-out and account checks for the sources that support the user's own account. */
internal object SourceAccounts {
    const val ZING_COOKIE_URL = "https://zingmp3.vn"
    const val ZING_LOGIN_URL = "https://zingmp3.vn/"
    private const val ZING_COOKIE_DOMAIN = ".zingmp3.vn"

    val zingCookies = ZingCookieStore()
    val nctTokens = AndroidNctTokenStore()

    // Prefer the clients the app registered so key and cookie caches are shared with playback.
    private val zingClient: ZingClient by lazy {
        KoinPlatform.getKoinOrNull()?.getOrNull<ZingClient>() ?: ZingClient.create(sharedOkHttpClient, zingCookies)
    }
    private val nctClient: NctClient by lazy {
        KoinPlatform.getKoinOrNull()?.getOrNull<NctClient>() ?: NctClient(nctTokens)
    }

    /** The Zing account behind the saved cookie, or null when it could not be checked. */
    suspend fun zingAccount(): ZingAccount? =
        try {
            zingClient.account()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** The NCT account behind the saved token, or null when it could not be checked. */
    suspend fun nctAccount(): NctAccount? =
        try {
            nctClient.account()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    suspend fun loginNct(
        username: String,
        password: String,
    ): NctToken = nctClient.loginWithPassword(username, password)

    suspend fun logoutNct() {
        nctClient.logout()
        nctTokens.clear()
    }

    suspend fun logoutZing() {
        zingCookies.clear()
        withContext(Dispatchers.Main) { clearZingWebCookies() }
    }

    /** Expires the zingmp3.vn cookies in the WebView too, so the next sign-in starts from a clean session. */
    fun clearZingWebCookies() {
        val manager = CookieManager.getInstance()
        val names =
            manager
                .getCookie(ZING_COOKIE_URL)
                ?.split(';')
                ?.map { it.substringBefore('=').trim() }
                ?.filter(String::isNotEmpty)
                .orEmpty()
        names.forEach { name ->
            manager.setCookie(ZING_COOKIE_URL, "$name=; Max-Age=0; Path=/")
            manager.setCookie(ZING_COOKIE_URL, "$name=; Max-Age=0; Path=/; Domain=$ZING_COOKIE_DOMAIN")
        }
        manager.flush()
    }
}
