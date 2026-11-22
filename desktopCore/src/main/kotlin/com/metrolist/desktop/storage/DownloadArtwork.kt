package com.metrolist.desktop.storage

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.utils.sizedArtworkUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.roundToInt

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

    /**
     * Decodes [bytes], scales the longer side down to [MAX_PX] (never up) with bilinear filtering and re-encodes a JPEG.
     * javax.imageio instead of Skia, so the headless core never loads Skiko. Formats ImageIO cannot read give null.
     */
    internal fun encode(bytes: ByteArray): DownloadCover? {
        val image = ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes))) ?: return null
        try {
            if (image.width <= 0 || image.height <= 0) return null
            val scale = minOf(1f, MAX_PX.toFloat() / maxOf(image.width, image.height))
            val width = (image.width * scale).roundToInt().coerceAtLeast(1)
            val height = (image.height * scale).roundToInt().coerceAtLeast(1)
            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = canvas.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.drawImage(image, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
            val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
            try {
                val params =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY / 100f
                    }
                val out = ByteArrayOutputStream()
                MemoryCacheImageOutputStream(out).use { stream ->
                    writer.output = stream
                    writer.write(null, IIOImage(canvas, null, null), params)
                }
                return DownloadCover(out.toByteArray(), width, height)
            } finally {
                writer.dispose()
            }
        } finally {
            image.flush()
        }
    }
}
