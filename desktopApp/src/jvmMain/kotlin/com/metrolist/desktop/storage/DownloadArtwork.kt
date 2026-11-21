package com.metrolist.desktop.storage

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.utils.sizedArtworkUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.roundToInt
import org.jetbrains.skia.Image as SkiaImage

/** Builds the embedded cover of downloaded and exported files: a JPEG of at most [MAX_PX] px on its longer side. */
internal object DownloadArtwork {
    const val MAX_PX = 1200
    private const val JPEG_QUALITY = 90
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_BYTES = 16L * 1024 * 1024

    /** [fallbackFile] is a locally stored thumbnail used when the network copy cannot be fetched. */
    suspend fun cover(
        url: String?,
        fallbackFile: File? = null,
    ): DownloadCover? =
        withContext(Dispatchers.IO) {
            try {
                val bytes =
                    url
                        ?.takeIf(String::isNotBlank)
                        ?.let { withTimeoutOrNull(TIMEOUT_MS) { fetch(sizedArtworkUrl(it, MAX_PX)) ?: fetch(it) } }
                        ?: fallbackFile?.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readBytes()
                        ?: return@withContext null
                encode(bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.warning("DownloadArtwork", "Could not prepare the download cover", error)
                null
            }
        }

    private fun fetch(url: String): ByteArray? {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
        return sharedOkHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body
            if (body.contentLength() > MAX_BYTES) return@use null
            body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        }
    }

    private fun encode(bytes: ByteArray): DownloadCover? {
        val image = SkiaImage.makeFromEncoded(bytes)
        try {
            if (image.width <= 0 || image.height <= 0) return null
            val scale = minOf(1f, MAX_PX.toFloat() / maxOf(image.width, image.height))
            val width = (image.width * scale).roundToInt().coerceAtLeast(1)
            val height = (image.height * scale).roundToInt().coerceAtLeast(1)
            val jpeg =
                Surface.makeRasterN32Premul(width, height).use { surface ->
                    Paint().use { paint ->
                        paint.isAntiAlias = true
                        surface.canvas.drawImageRect(
                            image,
                            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                            Rect.makeWH(width.toFloat(), height.toFloat()),
                            SamplingMode.LINEAR,
                            paint,
                            true,
                        )
                    }
                    surface.makeImageSnapshot().use { snapshot ->
                        snapshot.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.use { it.bytes }
                    }
                } ?: return null
            return DownloadCover(jpeg, width, height)
        } finally {
            image.close()
        }
    }
}
