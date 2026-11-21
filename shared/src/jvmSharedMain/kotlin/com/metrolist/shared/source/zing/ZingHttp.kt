package com.metrolist.shared.source.zing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ZingHttpResponse(
    val code: Int,
    val body: String,
    val setCookies: List<String> = emptyList(),
)

/**
 * Plain GET transport that throws IOException on network failure. Cookies are managed by [ZingSession],
 * so implementations must not keep a cookie jar.
 */
fun interface ZingHttp {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): ZingHttpResponse
}

class OkHttpZingHttp(
    private val client: OkHttpClient = OkHttpClient(),
) : ZingHttp {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): ZingHttpResponse =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            // OkHttp adds Accept-Encoding itself and then decompresses transparently; zingmp3.vn gzips every page.
            client.newCall(request).execute().use { response ->
                ZingHttpResponse(response.code, response.body.string(), response.headers("Set-Cookie"))
            }
        }
}
