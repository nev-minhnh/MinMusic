package com.metrolist.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {
    private val base = "https://cdn.example.com/a/master.m3u8?token=1"

    @Test
    fun masterPlaylistPicksHighestBandwidthAudioOnlyVariant() {
        val text =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.5"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2"
            https://other.example.com/hi.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=640x360
            video.m3u8
            """.trimIndent()
        val master = parseHlsPlaylist(text, base) as HlsPlaylist.Master
        assertEquals(3, master.variants.size)
        assertEquals("https://cdn.example.com/a/low/index.m3u8", master.variants[0].url)
        assertEquals("https://other.example.com/hi.m3u8", selectHlsAudioVariant(master.variants)?.url)
    }

    @Test
    fun masterWithOnlyVideoVariantsFallsBackToHighestBandwidth() {
        val variants =
            listOf(
                HlsVariant("a", 100, "avc1.42"),
                HlsVariant("b", 300, "avc1.64,mp4a.40.2"),
            )
        assertEquals("b", selectHlsAudioVariant(variants)?.url)
        assertNull(selectHlsAudioVariant(emptyList()))
    }

    @Test
    fun mediaPlaylistDetectsInitSegmentAndResolvesUrls() {
        val text =
            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-KEY:METHOD=NONE\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                "#EXTINF:10.0,\nseg0.m4s\n#EXTINF:10.0,\nseg1.m4s\n#EXT-X-ENDLIST\n"
        val media = parseHlsPlaylist(text, "https://cdn.example.com/a/b/list.m3u8") as HlsPlaylist.Media
        assertTrue(media.isFragmentedMp4)
        assertEquals("https://cdn.example.com/a/b/init.mp4", media.initSegmentUrl)
        assertEquals(listOf("https://cdn.example.com/a/b/seg0.m4s", "https://cdn.example.com/a/b/seg1.m4s"), media.segmentUrls)
    }

    @Test
    fun transportStreamPlaylistHasNoInitSegment() {
        val media = parseHlsPlaylist("#EXTM3U\n#EXTINF:6,\n1.ts\n#EXTINF:6,\n2.ts\n", base) as HlsPlaylist.Media
        assertFalse(media.isFragmentedMp4)
        assertEquals(2, media.segmentUrls.size)
    }

    @Test
    fun encryptedPlaylistsAreRejected() {
        val text = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:6,\n1.ts\n"
        assertThrows(HlsException.Encrypted::class.java) { parseHlsPlaylist(text, base) }
        val sample = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXTINF:6,\n1.ts\n"
        assertThrows(HlsException.Encrypted::class.java) { parseHlsPlaylist(sample, base) }
    }

    @Test
    fun attributesHandleQuotedCommas() {
        val attrs = parseHlsAttributes("BANDWIDTH=1000,CODECS=\"avc1.4d,mp4a.40.2\",NAME=\"x\"")
        assertEquals("1000", attrs["BANDWIDTH"])
        assertEquals("avc1.4d,mp4a.40.2", attrs["CODECS"])
        assertEquals("x", attrs["NAME"])
    }

    @Test
    fun nonPlaylistTextIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseHlsPlaylist("<html></html>", base) }
    }
}
