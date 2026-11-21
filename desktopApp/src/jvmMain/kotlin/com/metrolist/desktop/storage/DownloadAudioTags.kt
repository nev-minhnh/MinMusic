package com.metrolist.desktop.storage

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** A JPEG front cover, at most [DownloadArtwork.MAX_PX] on its longer side. */
internal class DownloadCover(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
)

/** Tags written into every downloaded or exported file (same fields as Android's AudioExportMetadata). */
internal data class DownloadTags(
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val lyrics: String? = null,
    val cover: DownloadCover? = null,
    /** `sc:<id>`, `zing:<id>`, `nct:<id>` or a YouTube Music watch URL. */
    val source: String? = null,
) {
    /** Keys for FFmpeg's MP4 (ipod) muxer, which maps them to iTunes atoms (lyrics -> ©lyr). */
    fun mp4Metadata(): Map<String, String> =
        buildMap {
            put("title", title)
            artist.takeIf(String::isNotBlank)?.let { put("artist", it) }
            album?.takeIf(String::isNotBlank)?.let { put("album", it) }
            albumArtist?.takeIf(String::isNotBlank)?.let { put("album_artist", it) }
            year?.takeIf { it > 0 }?.let { put("date", it.toString()) }
            trackNumber?.takeIf { it > 0 }?.let { track ->
                put("track", trackTotal?.takeIf { it > 0 }?.let { "$track/$it" } ?: track.toString())
            }
            lyrics?.takeIf(String::isNotBlank)?.let { put("lyrics", it) }
            source?.takeIf(String::isNotBlank)?.let { put("comment", it) }
        }

    /** Vorbis comments for Ogg Opus/Vorbis; the cover travels as a base64 METADATA_BLOCK_PICTURE. */
    fun vorbisMetadata(): Map<String, String> =
        buildMap {
            vorbisFields().forEach { (key, value) -> put(key, value) }
            cover?.let { put("METADATA_BLOCK_PICTURE", Base64.getEncoder().encodeToString(flacPicture(it))) }
        }

    fun vorbisFields(): List<Pair<String, String>> {
        val fields = mutableListOf<Pair<String, String>>()

        fun field(
            key: String,
            value: String?,
        ) {
            if (!value.isNullOrBlank()) fields += key to value
        }
        field("TITLE", title)
        field("ARTIST", artist)
        field("ALBUM", album)
        field("ALBUMARTIST", albumArtist)
        field("DATE", year?.takeIf { it > 0 }?.toString())
        field("TRACKNUMBER", trackNumber?.takeIf { it > 0 }?.toString())
        field("TRACKTOTAL", trackTotal?.takeIf { it > 0 }?.toString())
        field("LYRICS", lyrics)
        field("SOURCE", source)
        return fields
    }
}

/**
 * Writes an ID3v2.4 tag (UTF-8 text frames, no unsynchronisation) to the front of an MP3 file, like Android's
 * Id3v2Writer. Any ID3v2 tag already at the start is dropped; audio frames and a trailing ID3v1 block are copied as-is.
 */
internal fun writeId3Tags(
    input: File,
    output: File,
    tags: DownloadTags,
) {
    require(input.absoluteFile != output.absoluteFile) { "Input and output must differ" }
    val tag = buildId3v24Tag(tags)
    try {
        BufferedInputStream(input.inputStream()).use { stream ->
            skipLeadingId3v2(stream)
            output.outputStream().buffered().use { out ->
                out.write(tag)
                stream.copyTo(out, 64 * 1024)
            }
        }
    } catch (error: Throwable) {
        output.delete()
        throw error
    }
}

internal fun buildId3v24Tag(tags: DownloadTags): ByteArray {
    val frames = ByteArrayOutputStream()

    fun frame(
        id: String,
        body: ByteArray,
    ) {
        frames.write(id.toByteArray(Charsets.ISO_8859_1))
        frames.write(syncSafe(body.size))
        frames.write(byteArrayOf(0, 0))
        frames.write(body)
    }

    fun text(
        id: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) return
        frame(id, byteArrayOf(ID3_UTF8) + value.encodeToByteArray())
    }
    text("TIT2", tags.title)
    text("TPE1", tags.artist)
    text("TALB", tags.album)
    text("TPE2", tags.albumArtist)
    text("TDRC", tags.year?.takeIf { it > 0 }?.toString())
    tags.trackNumber?.takeIf { it > 0 }?.let { track ->
        text("TRCK", tags.trackTotal?.takeIf { it > 0 }?.let { "$track/$it" } ?: track.toString())
    }
    tags.lyrics?.takeIf(String::isNotBlank)?.let { lyrics ->
        // Language is a mandatory 3-byte field; "XXX" means unknown. The content descriptor is empty.
        frame("USLT", byteArrayOf(ID3_UTF8) + "XXX".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + lyrics.encodeToByteArray())
    }
    tags.source?.takeIf(String::isNotBlank)?.let { source ->
        frame("TXXX", byteArrayOf(ID3_UTF8) + "SOURCE".encodeToByteArray() + byteArrayOf(0) + source.encodeToByteArray())
    }
    tags.cover?.jpeg?.takeIf(ByteArray::isNotEmpty)?.let { jpeg ->
        // Encoding, MIME type, picture type 3 (front cover), empty description.
        frame("APIC", byteArrayOf(ID3_UTF8) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + jpeg)
    }
    val body = frames.toByteArray()
    check(body.size <= MAX_SYNC_SAFE) { "ID3 tag is too large" }
    return "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(4, 0, 0) + syncSafe(body.size) + body
}

private const val ID3_UTF8: Byte = 3
private const val MAX_SYNC_SAFE = (1 shl 28) - 1

private fun syncSafe(value: Int): ByteArray {
    require(value in 0..MAX_SYNC_SAFE) { "Value does not fit a sync-safe integer" }
    return byteArrayOf(
        ((value shr 21) and 0x7f).toByte(),
        ((value shr 14) and 0x7f).toByte(),
        ((value shr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )
}

// Consumes an existing leading ID3v2 tag (header, body, optional footer); leaves the stream untouched otherwise.
private fun skipLeadingId3v2(stream: BufferedInputStream) {
    stream.mark(10)
    val header = ByteArray(10)
    val read = readFully(stream, header)
    val isTag =
        read == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() &&
            header[3].toInt() != 0xff && header[4].toInt() != 0xff && (6..9).all { header[it].toInt() and 0x80 == 0 }
    if (!isTag) {
        stream.reset()
        return
    }
    val size = (6..9).fold(0L) { acc, index -> (acc shl 7) or header[index].toLong() }
    val footer = if (header[5].toInt() and 0x10 != 0) 10L else 0L
    var remaining = size + footer
    while (remaining > 0) {
        val skipped = stream.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (stream.read() < 0) break
            remaining--
        }
    }
}

private fun readFully(
    stream: InputStream,
    buffer: ByteArray,
): Int {
    var total = 0
    while (total < buffer.size) {
        val count = stream.read(buffer, total, buffer.size - total)
        if (count < 0) break
        total += count
    }
    return total
}

private const val FLAC_STREAMINFO = 0
private const val FLAC_VORBIS_COMMENT = 4
private const val FLAC_PICTURE = 6
private const val FLAC_MAX_BLOCK = 0xffffff

/**
 * Replaces the VORBIS_COMMENT and PICTURE metadata blocks of a FLAC file, like Android's FlacVorbisWriter. STREAMINFO
 * stays first, other blocks (seek table, padding, ...) are preserved and audio frames are copied byte-exact.
 */
internal fun writeFlacTags(
    input: File,
    output: File,
    tags: DownloadTags,
) {
    require(input.absoluteFile != output.absoluteFile) { "Input and output must differ" }
    try {
        DataInputStream(input.inputStream().buffered()).use { stream ->
            val magic = ByteArray(4)
            stream.readFully(magic)
            check(magic.contentEquals("fLaC".toByteArray(Charsets.ISO_8859_1))) { "Not a FLAC file" }
            val blocks = mutableListOf<Pair<Int, ByteArray>>()
            do {
                val header = stream.readInt()
                val last = header ushr 31 == 1
                val type = (header ushr 24) and 0x7f
                val body = ByteArray(header and FLAC_MAX_BLOCK).also(stream::readFully)
                if (type != FLAC_VORBIS_COMMENT && type != FLAC_PICTURE) blocks += type to body
            } while (!last)
            check(blocks.firstOrNull()?.first == FLAC_STREAMINFO) { "FLAC STREAMINFO must be the first block" }
            blocks += FLAC_VORBIS_COMMENT to vorbisCommentBlock(tags)
            tags.cover?.takeIf { it.jpeg.isNotEmpty() }?.let { blocks += FLAC_PICTURE to flacPicture(it) }
            output.outputStream().buffered().use { out ->
                out.write(magic)
                blocks.forEachIndexed { index, (type, body) ->
                    check(body.size <= FLAC_MAX_BLOCK) { "FLAC metadata block is too large" }
                    out.write((if (index == blocks.lastIndex) 0x80 else 0) or type)
                    out.write(body.size ushr 16)
                    out.write(body.size ushr 8)
                    out.write(body.size)
                    out.write(body)
                }
                stream.copyTo(out, 64 * 1024)
            }
        }
    } catch (error: Throwable) {
        output.delete()
        throw error
    }
}

private fun vorbisCommentBlock(tags: DownloadTags): ByteArray {
    val fields = tags.vorbisFields().map { (key, value) -> "$key=$value" }
    val vendor = "MinMusic".encodeToByteArray()
    val out = ByteArrayOutputStream()

    fun littleEndian(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    littleEndian(vendor.size)
    out.write(vendor)
    littleEndian(fields.size)
    fields.forEach { field ->
        val bytes = field.encodeToByteArray()
        littleEndian(bytes.size)
        out.write(bytes)
    }
    return out.toByteArray()
}

/** A FLAC PICTURE block body (front cover); Ogg files carry the same bytes base64-encoded. */
internal fun flacPicture(cover: DownloadCover): ByteArray {
    val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
    return ByteBuffer
        .allocate(32 + mime.size + cover.jpeg.size)
        .putInt(3) // front cover
        .putInt(mime.size)
        .put(mime)
        .putInt(0) // empty description
        .putInt(cover.width)
        .putInt(cover.height)
        .putInt(24) // colour depth
        .putInt(0) // not indexed
        .putInt(cover.jpeg.size)
        .put(cover.jpeg)
        .array()
}
