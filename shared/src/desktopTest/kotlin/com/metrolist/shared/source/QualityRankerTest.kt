package com.metrolist.shared.source

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualityRankerTest {
    @Test
    fun ordersLosslessThen320Mp3ThenOpusThenAac() {
        val flac = QualityRanker.tier("audio/flac", "flac", null)
        val mp3High = QualityRanker.tier("audio/mpeg", "mp3", 320_000)
        val youTubeOpus = QualityRanker.tier("audio/webm", "opus", 135_000)
        val soundCloudAac = QualityRanker.tier("application/x-mpegURL", "mp4a.40.2", 160_000)
        val youTubeAac = QualityRanker.tier("audio/mp4", "mp4a.40.2", 130_000)
        val mp3Low = QualityRanker.tier("audio/mpeg", "mp3", 128_000)

        assertTrue(flac > mp3High && mp3High > youTubeOpus && youTubeOpus > soundCloudAac && soundCloudAac > youTubeAac)
        assertEquals(youTubeAac, mp3Low)
        assertEquals(QualityRanker.Tier.UNKNOWN, QualityRanker.tier(null, null, 320_000))
    }

    @Test
    fun matchesTheSameRecordingOnly() {
        val youTube = song("Nơi Này Có Anh", listOf("Sơn Tùng M-TP"), 260)

        val featuring = song("Nơi Này Có Anh (feat. Someone)", listOf("Sơn Tùng M-TP, Someone"), 262)
        assertTrue(BestSourceSelector.isSameRecording(youTube, featuring))
        assertFalse(BestSourceSelector.isSameRecording(youTube, song("Nơi Này Có Anh", listOf("Sơn Tùng M-TP"), 264)))
        assertFalse(BestSourceSelector.isSameRecording(youTube, song("Nơi Này Có Anh (Remix)", listOf("Sơn Tùng M-TP"), 260)))
        assertFalse(BestSourceSelector.isSameRecording(youTube, song("Nơi Này Có Anh", listOf("Cover Singer"), 260)))
    }

    private fun song(
        title: String,
        artists: List<String>,
        durationSeconds: Long,
    ) = SongItem(
        id = "zing:ZW6ABCDE",
        title = title,
        thumbnail = null,
        artists = artists.map { ArtistTiny(null, it) },
        album = null,
        duration = durationSeconds,
    )
}
