package com.metrolist.shared.ui.components

import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceQualityLabelTest {
    private fun resource(
        codecs: String?,
        mimeType: String?,
        bitrate: Int?,
        itag: Int? = -1,
    ) = PlaybackResource(
        videoId = "sc:1056337348",
        url = "https://example.invalid/stream",
        headers = emptyMap(),
        loudnessDb = null,
        expiresAt = null,
        itag = itag,
        mimeType = mimeType,
        codecs = codecs,
        bitrate = bitrate,
    )

    @Test
    fun soundCloudHlsResourceShowsCodecAndBitrate() {
        assertEquals(AudioQualityLabel("AAC", 96), resource("mp4a.40.2", "application/x-mpegURL", 96_000).qualityLabel())
        assertEquals(AudioQualityLabel("AAC", 160), resource("mp4a.40.2", "application/x-mpegURL", 160_000).qualityLabel())
    }

    @Test
    fun youTubeItagWinsOverMeasuredBitrate() {
        assertEquals(AudioQualityLabel("Opus", 160), resource("opus", "audio/webm", 139_000, itag = 251).qualityLabel())
    }

    @Test
    fun unknownCodecHasNoLabel() {
        assertNull(resource(null, "audio/mp4", 128_000).qualityLabel())
        assertNull(resource("", null, null, itag = null).qualityLabel())
    }
}
