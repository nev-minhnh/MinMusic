package com.metrolist.android.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class MediaTagWritersTest {
    private val metadata =
        AudioExportMetadata(
            "Nắng ấm xa dần",
            "Sơn Tùng M-TP",
            "Album ♪",
            albumArtist = "Various",
            year = 2013,
            trackNumber = 3,
            trackTotal = 12,
            lyrics = "dòng 1\ndòng 2",
        )
    private val audio = ByteArray(2000) { (0xff - it % 3).toByte() } // bytes >= 0x80, like real frames
    private val cover = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())

    @Test
    fun id3RoundTripReplacesOldTagAndKeepsAudioBytes() {
        val input = Files.createTempFile("id3-in", ".mp3").toFile()
        val output = Files.createTempFile("id3-out", ".mp3").toFile()
        try {
            val oldBody = ByteArray(300) { 7 }
            val oldTag = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncSafe(oldBody.size) + oldBody
            val v1 = "TAG".toByteArray() + ByteArray(125)
            input.writeBytes(oldTag + audio + v1)
            writeId3Tags(input, output, metadata, cover, source = "zing")
            val bytes = output.readBytes()
            assertEquals("ID3", String(bytes, 0, 3))
            assertEquals(4, bytes[3].toInt())
            val size = readSyncSafe(bytes, 6)
            val frames = parseFrames(bytes.copyOfRange(10, 10 + size))
            assertEquals("Nắng ấm xa dần", text(frames.getValue("TIT2")))
            assertEquals("Sơn Tùng M-TP", text(frames.getValue("TPE1")))
            assertEquals("Album ♪", text(frames.getValue("TALB")))
            assertEquals("Various", text(frames.getValue("TPE2")))
            assertEquals("2013", text(frames.getValue("TDRC")))
            assertEquals("3/12", text(frames.getValue("TRCK")))
            val uslt = frames.getValue("USLT")
            assertEquals("XXX", String(uslt, 1, 3))
            assertEquals("dòng 1\ndòng 2", String(uslt, 5, uslt.size - 5, Charsets.UTF_8))
            assertEquals("SOURCE\u0000zing", String(frames.getValue("TXXX"), 1, 11, Charsets.UTF_8))
            val apic = frames.getValue("APIC")
            val mimeEnd = indexOfZero(apic, 1)
            assertEquals("image/jpeg", String(apic, 1, mimeEnd - 1))
            assertEquals(3, apic[mimeEnd + 1].toInt())
            assertArrayEquals(cover, apic.copyOfRange(mimeEnd + 3, apic.size))
            assertArrayEquals(audio + v1, bytes.copyOfRange(10 + size, bytes.size))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun id3SyncSafeSizesAreEncodedPerSpec() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0x7f), syncSafe(127))
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), syncSafe(128))
        assertArrayEquals(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f), syncSafe((1 shl 28) - 1))
        assertThrows(IllegalArgumentException::class.java) { syncSafe(1 shl 28) }
        val tag = buildId3v24Tag(metadata.copy(lyrics = "x".repeat(300)), null)
        tag.drop(6).take(4).forEach { assertTrue(it.toInt() and 0x80 == 0) }
        assertEquals(tag.size - 10, readSyncSafe(tag, 6))
    }

    @Test
    fun id3WithoutExistingTagPrependsTag() {
        val input = Files.createTempFile("id3-plain", ".mp3").toFile()
        val output = Files.createTempFile("id3-plain-out", ".mp3").toFile()
        try {
            input.writeBytes(audio)
            writeId3Tags(input, output, metadata.copy(album = null, lyrics = null, trackTotal = null), null)
            val bytes = output.readBytes()
            val size = readSyncSafe(bytes, 6)
            val frames = parseFrames(bytes.copyOfRange(10, 10 + size))
            assertNull(frames["TALB"])
            assertNull(frames["APIC"])
            assertEquals("3", text(frames.getValue("TRCK")))
            assertArrayEquals(audio, bytes.copyOfRange(10 + size, bytes.size))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun flacRoundTripKeepsStreamInfoFirstAndFramesExact() {
        val input = Files.createTempFile("flac-in", ".flac").toFile()
        val output = Files.createTempFile("flac-out", ".flac").toFile()
        try {
            val streamInfo = ByteArray(34) { it.toByte() }
            val oldComment = ByteArray(20) { 9 }
            val padding = ByteArray(16)
            val frame = byteArrayOf(0xff.toByte(), 0xf8.toByte(), 1, 2, 3, 4, 5)
            input.writeBytes(
                "fLaC".toByteArray() + flacBlock(0, streamInfo, false) + flacBlock(4, oldComment, false) +
                    flacBlock(1, padding, true) + frame,
            )
            writeFlacTags(input, output, metadata, cover, 500, 400)
            val bytes = output.readBytes()
            assertEquals("fLaC", String(bytes, 0, 4))
            var offset = 4
            val types = mutableListOf<Int>()
            val bodies = mutableMapOf<Int, ByteArray>()
            while (true) {
                val last = bytes[offset].toInt() and 0x80 != 0
                val type = bytes[offset].toInt() and 0x7f
                val length =
                    ((bytes[offset + 1].toInt() and 0xff) shl 16) or ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                        (bytes[offset + 3].toInt() and 0xff)
                types += type
                bodies[type] = bytes.copyOfRange(offset + 4, offset + 4 + length)
                offset += 4 + length
                if (last) break
            }
            assertEquals(listOf(0, 1, 4, 6), types)
            assertArrayEquals(streamInfo, bodies.getValue(0))
            assertArrayEquals(frame, bytes.copyOfRange(offset, bytes.size))
            val comments = parseVorbis(bodies.getValue(4))
            assertEquals("Nắng ấm xa dần", comments["TITLE"])
            assertEquals("Sơn Tùng M-TP", comments["ARTIST"])
            assertEquals("Album ♪", comments["ALBUM"])
            assertEquals("Various", comments["ALBUMARTIST"])
            assertEquals("2013", comments["DATE"])
            assertEquals("3", comments["TRACKNUMBER"])
            assertEquals("12", comments["TRACKTOTAL"])
            assertEquals("dòng 1\ndòng 2", comments["LYRICS"])
            val picture = ByteBuffer.wrap(bodies.getValue(6))
            assertEquals(3, picture.int)
            val mime = ByteArray(picture.int).also(picture::get)
            assertEquals("image/jpeg", String(mime))
            assertEquals(0, picture.int)
            assertEquals(500, picture.int)
            assertEquals(400, picture.int)
            picture.int
            picture.int
            val data = ByteArray(picture.int).also(picture::get)
            assertArrayEquals(cover, data)
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun flacRejectsFilesWithoutMagic() {
        val input = Files.createTempFile("not-flac", ".flac").toFile()
        val output = File(input.parentFile, "not-flac-out.flac")
        try {
            input.writeBytes(ByteArray(64))
            assertThrows(IllegalStateException::class.java) { writeFlacTags(input, output, metadata, null) }
            assertTrue(!output.exists())
        } finally {
            input.delete()
        }
    }

    private fun flacBlock(
        type: Int,
        body: ByteArray,
        last: Boolean,
    ): ByteArray =
        byteArrayOf(
            ((if (last) 0x80 else 0) or type).toByte(),
            (body.size shr 16).toByte(),
            (body.size shr 8).toByte(),
            body.size.toByte(),
        ) + body

    private fun parseVorbis(body: ByteArray): Map<String, String> {
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4 + buffer.int)
        return (0 until buffer.int)
            .associate {
                val field = String(ByteArray(buffer.int).also(buffer::get), Charsets.UTF_8)
                field.substringBefore("=") to field.substringAfter("=")
            }
    }

    private fun readSyncSafe(
        bytes: ByteArray,
        offset: Int,
    ): Int = (0 until 4).fold(0) { acc, i -> (acc shl 7) or bytes[offset + i].toInt() }

    private fun parseFrames(body: ByteArray): Map<String, ByteArray> {
        val frames = linkedMapOf<String, ByteArray>()
        var offset = 0
        while (offset < body.size) {
            val id = String(body, offset, 4)
            val size = readSyncSafe(body, offset + 4)
            frames[id] = body.copyOfRange(offset + 10, offset + 10 + size)
            offset += 10 + size
        }
        return frames
    }

    private fun text(frame: ByteArray): String {
        assertEquals(3, frame[0].toInt())
        return String(frame, 1, frame.size - 1, Charsets.UTF_8)
    }

    private fun indexOfZero(
        bytes: ByteArray,
        from: Int,
    ): Int = (from until bytes.size).first { bytes[it].toInt() == 0 }
}
