package com.metrolist.android.playback

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val FLAC_STREAMINFO = 0
private const val FLAC_VORBIS_COMMENT = 4
private const val FLAC_PICTURE = 6
private const val FLAC_MAX_BLOCK = 0xffffff

/**
 * Replaces the VORBIS_COMMENT and PICTURE metadata blocks of a FLAC file. STREAMINFO stays first,
 * other blocks (seek table, padding, ...) are preserved and audio frames are copied byte-exact.
 */
internal fun writeFlacTags(
    input: File,
    output: File,
    metadata: AudioExportMetadata,
    coverJpeg: ByteArray?,
    coverWidth: Int = 0,
    coverHeight: Int = 0,
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
            blocks += FLAC_VORBIS_COMMENT to vorbisComment(metadata)
            if (coverJpeg != null && coverJpeg.isNotEmpty()) {
                blocks += FLAC_PICTURE to flacPicture(coverJpeg, coverWidth, coverHeight)
            }
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

internal fun vorbisComment(metadata: AudioExportMetadata): ByteArray {
    val fields = mutableListOf<String>()

    fun add(
        key: String,
        value: String?,
    ) {
        if (!value.isNullOrBlank()) fields += "$key=$value"
    }
    add("TITLE", metadata.title)
    add("ARTIST", metadata.artist)
    add("ALBUM", metadata.album)
    add("ALBUMARTIST", metadata.albumArtist)
    add("DATE", metadata.year?.takeIf { it > 0 }?.toString())
    add("TRACKNUMBER", metadata.trackNumber?.takeIf { it > 0 }?.toString())
    add("TRACKTOTAL", metadata.trackTotal?.takeIf { it > 0 }?.toString())
    add("LYRICS", metadata.lyrics)
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

internal fun flacPicture(
    jpeg: ByteArray,
    width: Int,
    height: Int,
): ByteArray {
    val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
    return ByteBuffer
        .allocate(32 + mime.size + jpeg.size)
        .putInt(3) // front cover
        .putInt(mime.size)
        .put(mime)
        .putInt(0) // empty description
        .putInt(width)
        .putInt(height)
        .putInt(24) // colour depth
        .putInt(0) // not indexed
        .putInt(jpeg.size)
        .put(jpeg)
        .array()
}
