package com.metrolist.shared.source

import com.metrolist.shared.source.nct.NctItems
import com.metrolist.shared.source.soundcloud.SoundCloudItems
import com.metrolist.shared.source.soundcloud.SoundCloudPolicy
import com.metrolist.shared.source.soundcloud.SoundCloudStream
import com.metrolist.shared.source.soundcloud.SoundCloudTrack
import com.metrolist.shared.source.zing.ZingItems
import com.metrolist.shared.source.QualityRanker
import com.metrolist.shared.source.nct.NctStream
import com.metrolist.shared.source.zing.ZingAlbumRef
import com.metrolist.shared.source.zing.ZingArtistRef
import com.metrolist.shared.source.zing.ZingQuality
import com.metrolist.shared.source.zing.ZingSong
import com.metrolist.shared.source.zing.ZingStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** The adapters' output feeds the player (HLS vs progressive), downloads (format row) and the quality pick. */
class SourceAdapterMappingTest {
    @Test
    fun soundCloudHlsStreamBecomesAnExpiringAacResource() {
        val resource =
            SoundCloudItems.playbackResource(
                "sc:123",
                SoundCloudStream(
                    url = "https://cf-hls-media.sndcdn.com/playlist/abc.m3u8",
                    mimeType = "application/x-mpegURL",
                    bitrateKbps = 160,
                    codec = "aac",
                    isHls = true,
                    expiresAtMs = 1_800_000_240_000L,
                    preset = "aac_160k",
                ),
            )

        assertEquals("sc:123", resource.videoId)
        assertEquals(-1, resource.itag)
        assertEquals("mp4a.40.2", resource.codecs)
        assertEquals(160_000, resource.bitrate)
        assertEquals(1_800_000_240_000L, resource.expiresAt?.toEpochMilliseconds())
        assertEquals("sc", resource.clientName)
        assertTrue(!resource.isDirectFile)
        assertEquals(QualityRanker.Tier.AAC_STANDARD, QualityRanker.tier(resource))
    }

    @Test
    fun soundCloudPreviewTrackIsUnavailable() {
        val song =
            SoundCloudItems.song(
                SoundCloudTrack(
                    id = "sc:123",
                    numericId = 123,
                    title = "Track",
                    artist = "Uploader",
                    artistUrl = null,
                    durationMs = 30_500,
                    artworkUrl = null,
                    permalinkUrl = "https://soundcloud.com/uploader/track",
                    album = null,
                    policy = SoundCloudPolicy.SNIP,
                    streamable = true,
                ),
            )

        assertEquals(30L, song.duration)
        assertTrue(song.isUnavailable)
    }

    @Test
    fun zingSongAndLosslessStreamMapToNamespacedItems() {
        val song =
            ZingItems.song(
                ZingSong(
                    id = "zing:ZW6ABCDE",
                    zingId = "ZW6ABCDE",
                    title = "Song",
                    artists = emptyList<ZingArtistRef>(),
                    artistsText = "A, B",
                    album = ZingAlbumRef("zing:ZOABCDEF", "Album", null),
                    durationSeconds = 200,
                    artworkUrl = null,
                    isWorldWide = true,
                    streamingStatus = 1,
                    streamPrivileges = emptyList(),
                    downloadPrivileges = emptyList(),
                    hasLyric = true,
                ),
            )
        val lossless =
            ZingItems.playbackResource(
                "zing:ZW6ABCDE",
                ZingStream(
                    url = "https://a-vip.zmdcdn.me/x.flac?authen=exp=1800000000~hmac=y",
                    quality = ZingQuality.LOSSLESS,
                    mimeType = "audio/flac",
                    bitrateKbps = null,
                    expiresAtMs = 1_800_000_000_000L,
                ),
            )
        val mp3 =
            ZingItems.playbackResource(
                "zing:ZW6ABCDE",
                ZingStream("https://a128.zmdcdn.me/x?authen=exp=1800000000~hmac=y", ZingQuality.KBPS_320, "audio/mpeg", 320, null),
            )

        assertEquals(listOf("A", "B"), song.artists.map { it.name })
        assertEquals("zing:ZOABCDEF", song.album?.id)
        assertEquals(200L, song.duration)
        assertEquals("flac", lossless.codecs)
        assertEquals(QualityRanker.Tier.LOSSLESS, QualityRanker.tier(lossless))
        assertEquals(320_000, mp3.bitrate)
        assertEquals(QualityRanker.Tier.HIGH, QualityRanker.tier(mp3))
        assertTrue(mp3.isDirectFile)
    }

    @Test
    fun nctStreamKeepsItsBitrateAndExpiry() {
        val resource =
            NctItems.playbackResource(
                "nct:abc",
                NctStream("320", "https://stream.nct.vn/x.mp3?e=1800000000", false, true, "audio/mpeg", "mp3", 320_000, 1_800_000_000_000L),
            )

        assertEquals("nct", resource.clientName)
        assertEquals(1_800_000_000_000L, resource.expiresAt?.toEpochMilliseconds())
        assertEquals(QualityRanker.Tier.HIGH, QualityRanker.tier(resource))
    }
}
