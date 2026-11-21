package com.metrolist.android.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.shared.utils.canonicalArtworkUrl
import com.metrolist.shared.utils.persistentArtworkUrl
import com.metrolist.shared.utils.sizedArtworkUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

internal class DownloadedArtwork(
    private val context: Context,
) {
    private val directory = File(context.filesDir, "downloaded-artwork")

    fun file(url: String): File {
        val current = keyedFile(canonicalArtworkUrl(url))
        return current.takeIf { it.length() > 0 } ?: keyedFile(persistentArtworkUrl(url))
    }

    private fun keyedFile(url: String): File = File(directory, url.encodeUtf8().sha256().hex() + ".png")

    // Exported files get a JPEG cover up to EXPORT_ARTWORK_MAX_PX; the retained PNG is the offline fallback.
    suspend fun exportArtwork(url: String?): AudioExportArtwork? =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext null
            retain(url)
            try {
                val bitmap =
                    loadExportBitmap(url)
                        ?: file(url).takeIf { it.length() > 0 }?.let { BitmapFactory.decodeFile(it.absolutePath) }
                        ?: return@withContext null
                if (bitmap.width <= 0 || bitmap.height <= 0) return@withContext null
                val scale = minOf(1f, EXPORT_ARTWORK_MAX_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
                val scaled =
                    if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                            true,
                        )
                    } else {
                        bitmap
                    }
                val jpeg =
                    ByteArrayOutputStream().use { output ->
                        check(scaled.compress(Bitmap.CompressFormat.JPEG, EXPORT_ARTWORK_JPEG_QUALITY, output)) {
                            "Could not encode export artwork"
                        }
                        output.toByteArray()
                    }
                AudioExportArtwork(jpeg, "image/jpeg", scaled.width, scaled.height)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Could not read download artwork for export")
                null
            }
        }

    private suspend fun loadExportBitmap(url: String): Bitmap? =
        withTimeoutOrNull(EXPORT_ARTWORK_TIMEOUT_MS) {
            val result =
                context.imageLoader.execute(
                    ImageRequest
                        .Builder(context)
                        .data(sizedArtworkUrl(url, EXPORT_ARTWORK_MAX_PX))
                        .size(EXPORT_ARTWORK_MAX_PX)
                        .allowHardware(false)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .build(),
                )
            (result as? SuccessResult)?.image?.toBitmap()
        }

    suspend fun retain(url: String?) =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext
            val destination = file(url)
            if (destination.length() > 0) return@withContext
            try {
                val retained =
                    withTimeoutOrNull(30_000) {
                        val result =
                            context.imageLoader.execute(
                                ImageRequest
                                    .Builder(context)
                                    .data(url)
                                    .size(544)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .build(),
                            )
                        val image = (result as? SuccessResult)?.image ?: return@withTimeoutOrNull false
                        check(directory.mkdirs() || directory.isDirectory) { "Could not create download artwork directory" }
                        val temporary = File.createTempFile("artwork-", ".tmp", directory)
                        try {
                            temporary.outputStream().use { output ->
                                check(image.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                    "Could not encode download artwork"
                                }
                            }
                            check(temporary.renameTo(destination)) { "Could not publish download artwork" }
                            true
                        } finally {
                            temporary.delete()
                        }
                    }
                if (retained != true) Timber.w("Download artwork is missing; retrying when connectivity returns")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Could not retain download artwork")
            }
        }

    // Call while audio storage is locked so an in-flight download cannot lose its cover.
    fun prune(urls: List<String>) {
        val retained = urls.mapTo(mutableSetOf()) { file(it).name }
        directory.listFiles()?.filter { it.name !in retained }?.forEach {
            if (!it.delete()) Timber.w("Could not delete unused download artwork")
        }
    }
}

private const val EXPORT_ARTWORK_MAX_PX = 1200
private const val EXPORT_ARTWORK_JPEG_QUALITY = 90
private const val EXPORT_ARTWORK_TIMEOUT_MS = 15_000L
