package com.metrolist.shared.source.soundcloud

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import okhttp3.Request as OkHttpRequest

/** NewPipeExtractor [Downloader] that sends every request through the app's shared OkHttp client. */
class NewPipeOkHttpDownloader(
    private val client: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val body: RequestBody? =
            request.dataToSend()?.toRequestBody()
                ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null
        val builder =
            OkHttpRequest
                .Builder()
                .url(request.url())
                .method(method, body)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                throw ReCaptchaException("Rate limited by ${response.request.url.host}", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
