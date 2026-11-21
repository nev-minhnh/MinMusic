package com.metrolist.android.playback

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Writes an ID3v2.4 tag (UTF-8 text frames, no unsynchronisation) to the front of an MP3 file.
 * Any ID3v2 tag already at the start is dropped; audio frames and a trailing ID3v1 block are copied untouched.
 */
internal fun writeId3Tags(
    input: File,
    output: File,
    metadata: AudioExportMetadata,
    coverJpeg: ByteArray?,
    source: String? = null,
) {
    require(input.absoluteFile != output.absoluteFile) { "Input and output must differ" }
    val tag = buildId3v24Tag(metadata, coverJpeg, source)
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

internal fun buildId3v24Tag(
    metadata: AudioExportMetadata,
    coverJpeg: ByteArray?,
    source: String? = null,
): ByteArray {
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
        frame(id, byteArrayOf(UTF8) + value.encodeToByteArray())
    }
    text("TIT2", metadata.title)
    text("TPE1", metadata.artist)
    text("TALB", metadata.album)
    text("TPE2", metadata.albumArtist)
    text("TDRC", metadata.year?.takeIf { it > 0 }?.toString())
    metadata.trackNumber?.takeIf { it > 0 }?.let { track ->
        text("TRCK", metadata.trackTotal?.takeIf { it > 0 }?.let { "$track/$it" } ?: track.toString())
    }
    metadata.lyrics?.takeIf(String::isNotBlank)?.let { lyrics ->
        // Language is a mandatory 3-byte field; "XXX" means unknown. The content descriptor is empty.
        frame("USLT", byteArrayOf(UTF8) + "XXX".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + lyrics.encodeToByteArray())
    }
    if (!source.isNullOrBlank()) {
        frame("TXXX", byteArrayOf(UTF8) + "SOURCE".encodeToByteArray() + byteArrayOf(0) + source.encodeToByteArray())
    }
    if (coverJpeg != null && coverJpeg.isNotEmpty()) {
        // Encoding, MIME type, picture type 3 (front cover), empty description.
        frame("APIC", byteArrayOf(UTF8) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + coverJpeg)
    }
    val body = frames.toByteArray()
    check(body.size <= MAX_SYNC_SAFE) { "ID3 tag is too large" }
    return "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(4, 0, 0) + syncSafe(body.size) + body
}

private const val UTF8: Byte = 3
private const val MAX_SYNC_SAFE = (1 shl 28) - 1

internal fun syncSafe(value: Int): ByteArray {
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
