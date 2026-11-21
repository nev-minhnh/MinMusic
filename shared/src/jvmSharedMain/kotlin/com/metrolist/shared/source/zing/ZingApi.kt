package com.metrolist.shared.source.zing

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The only zingmp3.vn API paths this client may call. Ad, tracking and logging endpoints are deliberately absent,
 * and [ZingApi] cannot build a request for anything else.
 */
enum class ZingEndpoint(
    val path: String,
) {
    SEARCH_MULTI("search/multi"),
    SEARCH("search"),
    SONG_INFO("song/get/info"),
    SONG_STREAMING("song/get/streaming"),
    PLAYLIST("page/get/playlist"),
    ARTIST("page/get/artist"),
    CHART_HOME("page/get/chart-home"),
    WEEK_CHART("page/get/week-chart"),
    NEW_RELEASE_CHART("page/get/newrelease-chart"),
    TOP_100("page/get/top-100"),
    HUB_HOME("page/get/hub-home"),
    LYRIC("lyric/get/lyric"),
    USER_PROFILE("user/profile/get/info"),
    ;

    val apiPath: String get() = "/api/v2/$path"
}

/**
 * Signed GETs against the allow-listed [ZingEndpoint]s. A session error (-201/-403) refreshes the visitor cookie and
 * a signature or key error (-104/-116) extracts the keys again; each is retried once. No geo or IP headers are sent.
 */
class ZingApi(
    private val http: ZingHttp,
    private val keyProvider: ZingKeyProvider,
    private val session: ZingSession,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun get(
        endpoint: ZingEndpoint,
        params: Map<String, String> = emptyMap(),
    ): JsonElement {
        require(params.keys.none { it in reservedParams }) { "Reserved Zing parameter" }
        var sessionRetried = false
        var keysRetried = false
        while (true) {
            val keys = keyProvider.keys()
            val url = buildUrl(endpoint, params, keys, nowMs() / 1000)
            val headers =
                mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to HOME_URL,
                    "Cookie" to session.cookieHeader(),
                )
            val response =
                try {
                    http.get(url, headers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The message may carry the signed URL; keep only the type.
                    throw ZingException.Network(e::class.simpleName, e)
                }
            if (response.code !in 200..299) throw ZingException.Network("HTTP ${response.code}")
            val envelope = ZingMapper.envelope(response.body)
            when {
                envelope.err == 0 ->
                    return envelope.data?.takeUnless { it is JsonNull }
                        ?: throw ZingException.Api(0, "Missing data")

                envelope.err in ZingException.sessionErrors && !sessionRetried -> {
                    sessionRetried = true
                    session.invalidate()
                }

                envelope.err in ZingException.signatureErrors && !keysRetried -> {
                    keysRetried = true
                    keyProvider.refresh()
                }

                else -> throw ZingException.fromApi(envelope.err, envelope.msg)
            }
        }
    }

    companion object {
        const val HOME_URL = "https://zingmp3.vn/"
        const val DEFAULT_USER_AGENT = "MinMusic"
        private const val API_ORIGIN = "https://zingmp3.vn"

        private val reservedParams = setOf("ctime", "version", "sig", "apiKey")

        fun buildUrl(
            endpoint: ZingEndpoint,
            params: Map<String, String>,
            keys: ZingKeys,
            ctimeSeconds: Long,
        ): String {
            val signed = params + mapOf("ctime" to ctimeSeconds.toString(), "version" to keys.version)
            val sig = ZingSigner.sign(endpoint.apiPath, signed, keys.secret)
            return API_ORIGIN
                .toHttpUrl()
                .newBuilder()
                .encodedPath(endpoint.apiPath)
                .apply {
                    signed.forEach { (name, value) -> addQueryParameter(name, value) }
                    addQueryParameter("sig", sig)
                    addQueryParameter("apiKey", keys.apiKey)
                }.build()
                .toString()
        }
    }
}
