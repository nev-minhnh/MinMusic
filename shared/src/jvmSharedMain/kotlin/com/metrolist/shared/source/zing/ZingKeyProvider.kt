package com.metrolist.shared.source.zing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ZingKeys(
    val version: String,
    val apiKey: String,
    val secret: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ZingKeys && other.version == version && other.apiKey == apiKey && other.secret == secret

    override fun hashCode(): Int = (version.hashCode() * 31 + apiKey.hashCode()) * 31 + secret.hashCode()

    // Keeps the signing secret out of logs and crash reports.
    override fun toString(): String = "ZingKeys(version=$version)"
}

/** Pulls the web bundle version, API key and signing secret out of the zingmp3.vn page and its main.min.js. */
object ZingKeyExtractor {
    class MainScript(
        val url: String,
        val version: String,
    )

    private val mainScriptPattern =
        Regex("""https://zmdjs\.zmdcdn\.me/zmp3-desktop/v([0-9][0-9A-Za-z.\-]*)/static/js/main\.min\.js""")

    // ZingMP3Explode: ;var .="(.{32})",.="(.{32})",.={publicKey: (apiKey first, then secret).
    private val keysPattern =
        Regex("""var\s+[\w$]+="([A-Za-z0-9]{32})",\s*[\w$]+="([A-Za-z0-9]{32})",\s*[\w$]+=\{publicKey:""")

    fun findMainScript(homeHtml: String): MainScript? =
        mainScriptPattern.find(homeHtml)?.let { MainScript(it.value, it.groupValues[1]) }

    /** Returns the API key and the secret, in that order. */
    fun findKeys(mainScript: String): Pair<String, String>? =
        keysPattern.find(mainScript)?.let { it.groupValues[1] to it.groupValues[2] }
}

/**
 * Supplies [ZingKeys]: extracted at runtime, cached for [cacheTtlMs], with [fallback] (the values the maintained
 * open-source clients ship) when the page or bundle cannot be read or parsed.
 */
class ZingKeyProvider(
    private val http: ZingHttp,
    private val userAgent: String = ZingApi.DEFAULT_USER_AGENT,
    private val now: () -> Long = System::currentTimeMillis,
    private val fallback: ZingKeys = DEFAULT_KEYS,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
) {
    private val mutex = Mutex()
    private var cached: ZingKeys? = null
    private var expiresAtMs = 0L

    suspend fun keys(): ZingKeys =
        mutex.withLock {
            cached?.takeIf { now() < expiresAtMs } ?: load(forced = false)
        }

    /** Extracts again, ignoring the cache. Called once after the API rejects a signature or key. */
    suspend fun refresh(): ZingKeys = mutex.withLock { load(forced = true) }

    private suspend fun load(forced: Boolean): ZingKeys {
        val extracted =
            try {
                extract()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        val previous = cached
        val keys =
            when {
                extracted != null -> extracted
                // The rejected keys must not be reused when a forced refresh cannot extract new ones.
                forced && previous != fallback -> fallback
                else -> previous ?: fallback
            }
        cached = keys
        expiresAtMs = now() + if (extracted != null) cacheTtlMs else FAILED_EXTRACTION_RETRY_MS
        return keys
    }

    private suspend fun extract(): ZingKeys? {
        val home = http.get(ZingApi.HOME_URL, mapOf("User-Agent" to userAgent))
        if (home.code !in 200..299) return null
        val script = ZingKeyExtractor.findMainScript(home.body) ?: return null
        val bundle = http.get(script.url, mapOf("User-Agent" to userAgent, "Referer" to ZingApi.HOME_URL))
        if (bundle.code !in 200..299) return null
        // A changed bundle layout breaks the key pattern before the keys themselves change.
        val (apiKey, secret) = ZingKeyExtractor.findKeys(bundle.body) ?: (fallback.apiKey to fallback.secret)
        return ZingKeys(script.version, apiKey, secret)
    }

    companion object {
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        const val FAILED_EXTRACTION_RETRY_MS = 60L * 60 * 1000

        /** Values shipped by ZingMP3Explode and NodeLink, matching zingmp3.vn v1.22.4 on 2026-11-21. */
        val DEFAULT_KEYS =
            ZingKeys(
                version = "1.22.4",
                apiKey = "X5BM3w8N7MKozC0B85o4KMlzLZKhV00y",
                secret = "acOrvUS15XRW2o9JksiK1KgQ6Vbds8ZW",
            )
    }
}
