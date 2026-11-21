package com.metrolist.shared.source.nct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures were recorded from graph.nct.vn on 2026-11-21; login.json is reduced from the nct.js contract. */
class NctContractTest {
    @Test
    fun songDetailMapsMetadataAndStreams() {
        val song = assertNotNull(NctParser.song(NctParser.envelope(fixture("song-detail.json"), 200).data))

        assertEquals("nct:5QYIeJtQiemn", song.id)
        assertEquals("Đừng Làm Trái Tim Anh Đau", song.title)
        assertEquals(listOf("Sơn Tùng M-TP"), song.artists.map { it.name })
        assertEquals(279_000L, song.durationMs)
        assertTrue(song.artworkUrl!!.startsWith("https://image-cdn.nct.vn/song/"))
        assertNull(song.album)
        assertEquals(listOf("128", "320", "lossless"), song.streams.map { it.quality })
        val lossless = song.streams.single { it.quality == "lossless" }
        assertTrue(lossless.onlyVip)
        assertFalse(lossless.available)
        assertEquals("audio/flac", lossless.mimeType)
    }

    @Test
    fun freeSessionNeverGetsVipOnlyStream() {
        val streams = NctParser.song(NctParser.envelope(fixture("song-detail.json"), 200).data)!!.streams

        val free = NctParser.selectStream(streams, vip = false)
        assertEquals("320", free.quality)
        assertFalse(free.onlyVip)
        assertEquals("audio/mpeg", free.mimeType)
        assertEquals("mp3", free.codec)
        assertEquals(320_000, free.bitrate)
        assertEquals(1_795_836_724_000L, free.expiresAtMs)

        assertEquals("lossless", NctParser.selectStream(streams, vip = true).quality)

        val vipOnly = streams.map { it.copy(onlyVip = true) }
        assertFailsWith<NctException.VipRequired> { NctParser.selectStream(vipOnly, vip = false) }
        assertFailsWith<NctException.NotFound> { NctParser.selectStream(emptyList(), vip = true) }
    }

    @Test
    fun loginResponseYieldsTokenAndEntitlement() {
        val token = NctParser.login(NctParser.envelope(fixture("login.json"), 200).data)

        assertTrue(token.value.startsWith("eyJ"))
        assertEquals(1_800_000_000_000L, token.expiresAtMs)
        assertTrue(token.account.loggedIn)
        assertEquals(NctEntitlement.VIP, token.account.entitlement)
        assertEquals("1234567", token.account.userId)
        assertFalse(token.toString().contains(token.value))
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", NctClient.md5Hex("password"))

        assertFailsWith<NctException.AuthFailed> { NctParser.envelope(fixture("error-not-signed-in.json"), 200) }
        assertFailsWith<NctException.NotFound> { NctParser.envelope(fixture("error-not-found.json"), 200) }
        assertFailsWith<NctException.Blocked> { NctParser.envelope("<html>Access denied</html>", 200) }
    }

    @Test
    fun lyricsMapPlainTextAndDecryptTimedFile() {
        val detail = assertNotNull(NctParser.lyricDetail(NctParser.envelope(fixture("lyric-detail.json"), 200).data))

        assertEquals("[Verse]", detail.plain!!.lineSequence().first())
        assertFalse(detail.plain!!.contains('\r'))
        assertEquals("https://lyric.nct.vn/2026/07/01/d/d/q/9/1782895051317.lrc", detail.timedLyricUrl)
        assertFalse(detail.aiGenerated)

        val synced = assertNotNull(NctParser.decodeTimedLyric(fixture("lyric-timed.lrc.hex"), detail.decryptKey))
        val lines = synced.lines()
        assertEquals(4, lines.size)
        assertEquals("[00:30.37]Hình như trong lòng anh đã không còn hình bóng ai ngoài em đâu", lines.first())
        assertEquals("[00:41.47]Cứ cố quá đâm ra lại hâm", lines.last())
        assertNull(NctParser.decodeTimedLyric(fixture("lyric-timed.lrc.hex"), "wrong-key"))
    }

    private fun fixture(name: String): String =
        assertNotNull(javaClass.getResourceAsStream("/nct/$name"), name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
