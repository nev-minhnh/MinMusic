package com.metrolist.shared.source.zing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Supplies the user's own logged-in zingmp3.vn cookie header, or null for a visitor session. */
fun interface CredentialProvider {
    suspend fun cookieHeader(): String?
}

/**
 * Visitor cookies from a plain GET of https://zingmp3.vn/ (zmp3_rqid, zmp3_app_version.1), kept for [ttlMs].
 * A cookie header from [credentials] is merged on top and wins on name clashes. Nothing is persisted here.
 */
class ZingSession(
    private val http: ZingHttp,
    private val credentials: CredentialProvider? = null,
    private val userAgent: String = ZingApi.DEFAULT_USER_AGENT,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = VISITOR_COOKIE_TTL_MS,
) {
    private val mutex = Mutex()
    private var visitor: Map<String, String>? = null
    private var expiresAtMs = 0L

    suspend fun cookieHeader(): String {
        val visitorCookies = visitorCookies()
        val login = credentials?.cookieHeader()?.takeIf { it.isNotBlank() }
        return mergeCookies(visitorCookies, login?.let(::parseCookieHeader).orEmpty())
    }

    suspend fun hasCredentials(): Boolean = !credentials?.cookieHeader().isNullOrBlank()

    suspend fun invalidate() {
        mutex.withLock {
            visitor = null
            expiresAtMs = 0L
        }
    }

    private suspend fun visitorCookies(): Map<String, String> =
        mutex.withLock {
            visitor?.takeIf { now() < expiresAtMs } ?: fetchVisitorCookies().also {
                visitor = it
                expiresAtMs = now() + ttlMs
            }
        }

    private suspend fun fetchVisitorCookies(): Map<String, String> {
        val response =
            try {
                http.get(ZingApi.HOME_URL, mapOf("User-Agent" to userAgent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ZingException.Network(e.message, e)
            }
        if (response.code !in 200..299) throw ZingException.Network("HTTP ${response.code}")
        return parseSetCookies(response.setCookies)
    }

    companion object {
        const val VISITOR_COOKIE_TTL_MS = 20L * 60 * 1000

        fun parseSetCookies(headers: List<String>): Map<String, String> =
            buildMap {
                for (header in headers) {
                    val pair = header.substringBefore(';')
                    val name = pair.substringBefore('=', "").trim()
                    if (name.isNotEmpty()) put(name, pair.substringAfter('=').trim())
                }
            }

        fun parseCookieHeader(header: String): Map<String, String> =
            buildMap {
                for (part in header.split(';')) {
                    val name = part.substringBefore('=', "").trim()
                    if (name.isNotEmpty()) put(name, part.substringAfter('=').trim())
                }
            }

        fun mergeCookies(
            visitor: Map<String, String>,
            login: Map<String, String>,
        ): String = (visitor + login).entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }
}
