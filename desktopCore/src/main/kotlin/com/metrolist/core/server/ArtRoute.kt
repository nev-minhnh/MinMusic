package com.metrolist.core.server

import com.metrolist.core.CoreConfig
import com.metrolist.core.CoreLog
import com.metrolist.core.api.library.CoverFiles
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.shared.utils.XdgUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * `GET /art/<kind>/<id>?w=<px>&k=<artToken>` (api.md 1.10): local artwork as a JPEG resized to a size bucket. Kinds:
 * `cover` (user-album covers stored by `userAlbum.setCover`) and `local` (the artwork files kept beside downloaded and
 * cached songs). A wrong or missing `k` is 403. Resized files are cached under `cache\metrolist\image_cache\core\`;
 * the answer is `Cache-Control: max-age=31536000, immutable` because ids carry a content hash. No Skia, only ImageIO.
 */
fun Route.installArtRoute() {
    get("/art/{kind}/{id}") {
        val kind = call.parameters["kind"].orEmpty()
        val id = call.parameters["id"].orEmpty()
        if (!ArtRequests.tokenOk(call.request.queryParameters["k"], CoreConfig.artToken)) {
            call.respond(HttpStatusCode.Forbidden)
            return@get
        }
        val bucket = ArtRequests.bucket(call.request.queryParameters["w"]?.toIntOrNull())
        val bytes =
            withContext(Dispatchers.IO) {
                val source = ArtSources.find(kind, id)
                if (source == null) null else ArtCache.jpeg(kind, id, bucket, source)
            }
        if (bytes == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "max-age=31536000, immutable")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
}

/** Request rules of the art endpoint; pure. */
object ArtRequests {
    val BUCKETS = intArrayOf(64, 128, 256, 384, 512, 768, 1080)
    const val DEFAULT_BUCKET = 512

    /** The smallest bucket that holds [width] (api.md 1.10), the largest for anything bigger, 512 when none was asked. */
    fun bucket(width: Int?): Int {
        if (width == null || width <= 0) return DEFAULT_BUCKET
        return BUCKETS.firstOrNull { it >= width } ?: BUCKETS.last()
    }

    /** Constant-time comparison; an empty expected token never matches. */
    fun tokenOk(
        candidate: String?,
        expected: String,
    ): Boolean =
        candidate != null &&
            expected.isNotEmpty() &&
            MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}

/** Where the artwork of an art URL lives on disk. */
internal object ArtSources {
    fun find(
        kind: String,
        id: String,
    ): File? =
        when (kind) {
            "cover" -> CoverFiles.findById(id)
            "local" -> localArt(id)
            else -> null
        }

    /** The artwork file kept with a downloaded or cached song; for a `file:` id an image next to the audio file. */
    private fun localArt(id: String): File? {
        DownloadManager.downloadedFiles.value
            .firstOrNull { it.songId == id }
            ?.thumbnailFilePath
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { return it }
        SongCacheManager
            .getCachedSongs()
            .firstOrNull { it.songId == id }
            ?.thumbnailPath
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { return it }
        if (!id.startsWith("file:")) return null
        val folder = runCatching { File(URI(id)).parentFile }.getOrNull() ?: return null
        return listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png").map { File(folder, it) }.firstOrNull { it.isFile }
    }
}

/** Resized JPEGs on disk, one file per (kind, id, bucket); concurrent requests for the same file render it once. */
internal object ArtCache {
    private val directory: File get() = File(XdgUtils.getXdgCacheHome(), "metrolist/image_cache/core")
    private val locks = ConcurrentHashMap<String, Any>()

    fun jpeg(
        kind: String,
        id: String,
        bucket: Int,
        source: File,
    ): ByteArray? {
        val key = hex(MessageDigest.getInstance("SHA-1").digest("$kind/$id".toByteArray(Charsets.UTF_8))).take(24) + "-$bucket"
        val target = File(directory, "$key.jpg")
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            if (target.isFile && target.lastModified() >= source.lastModified()) {
                runCatching { return target.readBytes() }
            }
            val bytes =
                try {
                    ArtImages.resizeToJpeg(source, bucket)
                } catch (error: IOException) {
                    CoreLog.error("art: cannot render $kind/$id", error)
                    return null
                }
            runCatching {
                directory.mkdirs()
                val temp = File.createTempFile("art", ".tmp", directory)
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
                temp.delete()
            }
            return bytes
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/** ImageIO resize and JPEG encode; pure over files. */
object ArtImages {
    const val QUALITY = 0.85f

    /** Size of [srcWidth] x [srcHeight] fitted into a [bucket] box, never enlarged, aspect ratio kept. */
    fun fit(
        srcWidth: Int,
        srcHeight: Int,
        bucket: Int,
    ): Pair<Int, Int> {
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= bucket) return srcWidth to srcHeight
        val ratio = bucket.toDouble() / longest
        return maxOf(1, Math.round(srcWidth * ratio).toInt()) to maxOf(1, Math.round(srcHeight * ratio).toInt())
    }

    fun resizeToJpeg(
        source: File,
        bucket: Int,
    ): ByteArray {
        ImageIO.setUseCache(false)
        val image = ImageIO.read(source) ?: throw IOException("not an image: ${source.name}")
        val (width, height) = fit(image.width, image.height, bucket)
        return encode(scale(image, width, height))
    }

    /** RGB copy of [image] at the target size; large reductions go in halving steps so thin lines do not alias. */
    fun scale(
        image: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        var current = toRgb(image, image.width, image.height)
        while (current.width / 2 >= width && current.height / 2 >= height) {
            current = draw(current, current.width / 2, current.height / 2)
        }
        return if (current.width == width && current.height == height) current else draw(current, width, height)
    }

    private fun toRgb(
        image: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage = if (image.type == BufferedImage.TYPE_INT_RGB) image else draw(image, width, height)

    private fun draw(
        image: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(image, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return out
    }

    fun encode(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = QUALITY
                }
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }
}
