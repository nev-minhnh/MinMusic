package com.metrolist.core.api.library

import com.metrolist.core.server.badRequest
import com.metrolist.shared.utils.XdgUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * User-album covers on disk: validation of a picked image (ImageIO, no Skia), the stored file
 * `<dir>/<playlistId>-<sha8>.<ext>` and the id `<playlistId>-<sha8>` that `core:cover/<id>` and `/art/cover/<id>` use.
 * The hash is part of the name, so a changed cover has a new URL and the immutable HTTP cache never goes stale.
 */
object CoverFiles {
    const val MAX_BYTES = 16L * 1024 * 1024
    const val MAX_PIXELS = 16_700_000L

    /** The v1 cover directory: `playlist_covers` under the app data directory. */
    val dir: File get() = File(XdgUtils.getXdgDataHome(), "metrolist/playlist_covers")

    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val SAFE_COVER_ID = Regex("[A-Za-z0-9_-]{1,96}")

    fun isSafePlaylistId(id: String): Boolean = SAFE_ID.matches(id)

    fun isSafeCoverId(id: String): Boolean = SAFE_COVER_ID.matches(id)

    class Validated(
        val bytes: ByteArray,
        val extension: String,
        val width: Int,
        val height: Int,
    )

    /** `bad_request` unless the image is within the pixel limit; split out so it can be tested without a picture. */
    fun checkDimensions(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) badRequest("image has no size")
        if (width.toLong() * height > MAX_PIXELS) badRequest("image is larger than 16.7 megapixels")
    }

    /** Reads [file] fully and checks size, format and pixel count; the picture is decoded once to prove it is readable. */
    fun validate(file: File): Validated {
        if (!file.isFile) badRequest("cover path is not a file")
        if (file.length() > MAX_BYTES) badRequest("cover is larger than 16 MiB")
        val bytes = file.readBytes()
        ImageIO.setUseCache(false)
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: badRequest("cover is not a readable image")
        stream.use {
            val readers = ImageIO.getImageReaders(it)
            if (!readers.hasNext()) badRequest("cover is not a known image format")
            val reader = readers.next()
            try {
                reader.input = it
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                checkDimensions(width, height)
                reader.read(0) ?: badRequest("cover could not be decoded")
                return Validated(bytes, extensionOf(reader.formatName), width, height)
            } catch (error: java.io.IOException) {
                badRequest("cover could not be decoded: ${error.message}")
            } finally {
                reader.dispose()
            }
        }
    }

    fun extensionOf(formatName: String): String =
        when (val name = formatName.lowercase()) {
            "jpeg" -> "jpg"
            else -> name.filter { it.isLetterOrDigit() }.ifEmpty { "img" }
        }

    fun sha8(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .take(4)
            .joinToString("") { "%02x".format(it) }

    class Stored(
        val id: String,
        val file: File,
    )

    /** Validates [source] and stores it as the cover of [playlistId] in [directory]; older covers of the same playlist are removed. */
    fun store(
        playlistId: String,
        source: File,
        directory: File = dir,
    ): Stored {
        if (!isSafePlaylistId(playlistId)) badRequest("bad playlist id")
        val image = validate(source)
        val id = "$playlistId-${sha8(image.bytes)}"
        directory.mkdirs()
        val target = File(directory, "$id.${image.extension}")
        if (!target.isFile || target.length() != image.bytes.size.toLong()) {
            val temp = File.createTempFile("cover", ".tmp", directory)
            try {
                temp.writeBytes(image.bytes)
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
        removeCovers(playlistId, directory, keep = target)
        return Stored(id, target)
    }

    /** Deletes the stored covers of [playlistId] except [keep]. */
    fun removeCovers(
        playlistId: String,
        directory: File = dir,
        keep: File? = null,
    ) {
        if (!isSafePlaylistId(playlistId)) return
        directory
            .listFiles { file -> file.isFile && file.name.startsWith("$playlistId-") && file != keep }
            ?.forEach { it.delete() }
    }

    /** The cover id of a `customThumbnailUrl` (a path or `file:` URI inside [directory]); null for anything else. */
    fun idOfStoredCover(
        value: String?,
        directory: File = dir,
    ): String? {
        if (value.isNullOrBlank()) return null
        val file =
            try {
                if (value.startsWith("file:")) File(URI(value)) else File(value)
            } catch (_: Exception) {
                return null
            }
        val parent = file.absoluteFile.parentFile ?: return null
        return if (parent.normalize() == directory.absoluteFile.normalize()) file.nameWithoutExtension else null
    }

    /** The stored cover with id [id] (`<playlistId>-<sha8>`), or null. */
    fun findById(
        id: String,
        directory: File = dir,
    ): File? {
        if (!isSafeCoverId(id)) return null
        return directory.listFiles { file -> file.isFile && file.nameWithoutExtension == id }?.firstOrNull()
    }
}
