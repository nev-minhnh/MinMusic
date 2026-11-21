package com.metrolist.shared.source.nct

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Transport for the NhacCuaTui Android-app API on graph.nct.vn.
 *
 * Only allow-listed paths are reachable, so ad, tracking, push and device endpoints can never be called.
 * The OkHttp client is private to NCT: it has no logging interceptors and follows no redirects, because
 * OkHttp would forward the custom `x-token` header to a redirect target on another host.
 */
internal class NctApi(
    private val client: OkHttpClient = defaultClient,
) {
    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        token: String? = null,
    ): NctParser.Envelope {
        val url = graphUrl(path, query)
        val request = baseRequest(url, token).get().build()
        return execute(request)
    }

    /** Sends the login form. The body carries the MD5 password hash and is never logged or kept. */
    suspend fun postForm(
        path: String,
        form: Map<String, String>,
    ): NctParser.Envelope {
        val url = graphUrl(path, emptyMap())
        val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        return execute(baseRequest(url, token = null).post(body).build())
    }

    /** Fetches a timed-lyric file from the lyric CDN; no session header is sent there. */
    suspend fun getLyricFile(url: String): String {
        val httpUrl =
            runCatching { url.toHttpUrl() }.getOrNull()
                ?.takeIf { it.isHttps && it.host == LYRIC_HOST && it.encodedPath.endsWith(".lrc") }
                ?: throw NctException.Unexpected(null)
        val request =
            Request
                .Builder()
                .url(httpUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        return withContext(Dispatchers.IO) {
            call(request).use { response ->
                if (response.isRedirect || !response.isSuccessful) throw httpFailure(response.code)
                response.body.string()
            }
        }
    }

    private suspend fun execute(request: Request): NctParser.Envelope =
        withContext(Dispatchers.IO) {
            call(request).use { response ->
                if (response.isRedirect || !response.isSuccessful) throw httpFailure(response.code)
                val body =
                    try {
                        response.body.string()
                    } catch (e: IOException) {
                        throw NctException.Network(e, response.code)
                    }
                NctParser.envelope(body, response.code)
            }
        }

    private fun httpFailure(code: Int): NctException =
        when {
            code in 300..399 || code == 401 || code == 403 || code == 429 || code == 451 -> NctException.Blocked(code)
            code == 404 -> NctException.NotFound(null)
            else -> NctException.Network(null, code)
        }

    private fun baseRequest(
        url: HttpUrl,
        token: String?,
    ): Request.Builder =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-os", "android")
            .apply { if (!token.isNullOrEmpty()) header("x-token", token) }

    private suspend fun call(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(NctException.Network(e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                },
            )
        }

    companion object {
        private const val GRAPH_HOST = "graph.nct.vn"
        private const val LYRIC_HOST = "lyric.nct.vn"
        private const val USER_AGENT = "okhttp/4.12.0"

        const val SEARCH_SONG = "/api/v1/search/song"
        const val HOT_KEYWORDS = "/api/v1/search/top-key/keyword-hot"
        const val SONG_DETAIL = "/api/v1/song/detail/"
        const val SONG_LYRIC = "/api/v2/song/lyric/detail"
        const val PLAYLIST_DETAIL = "/api/v1/playlist/detail/"
        const val CHARTS = "/api/v1/playlist/charts"
        const val LOGIN = "/api/v1/user/login/loginByAccount"
        const val ACCOUNT_INFO = "/api/v1/user/account/info"

        private val exactPaths = setOf(SEARCH_SONG, HOT_KEYWORDS, SONG_LYRIC, CHARTS, LOGIN, ACCOUNT_INFO)
        private val keyedPaths = setOf(SONG_DETAIL, PLAYLIST_DETAIL, "$CHARTS/")
        private val keyPattern = Regex("[A-Za-z0-9_-]{1,64}")

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        fun isValidKey(key: String): Boolean = keyPattern.matches(key)

        fun isAllowedPath(path: String): Boolean =
            path in exactPaths ||
                keyedPaths.any { prefix -> path.startsWith(prefix) && isValidKey(path.removePrefix(prefix)) }

        private fun graphUrl(
            path: String,
            query: Map<String, String>,
        ): HttpUrl {
            require(isAllowedPath(path)) { "NCT path is not allow-listed" }
            return HttpUrl
                .Builder()
                .scheme("https")
                .host(GRAPH_HOST)
                .encodedPath(path)
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
        }
    }
}
