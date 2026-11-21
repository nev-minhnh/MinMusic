package com.metrolist.shared.source

import com.metrolist.shared.youtube.playback.PlaybackResource

/**
 * Orders audio streams by perceived quality: lossless > 320 kbps MP3 (or AAC/Opus at 192 kbps and up) > Opus ~160 >
 * AAC ~160 > AAC 128 / MP3 128. The declared bitrate (bits per second) moves a stream between tiers and breaks ties
 * inside a tier.
 */
object QualityRanker {
    enum class Tier {
        UNKNOWN,
        LOW,
        AAC_STANDARD,
        OPUS_STANDARD,
        HIGH,
        LOSSLESS,
    }

    private enum class Codec { LOSSLESS, MP3, OPUS, AAC }

    fun tier(resource: PlaybackResource): Tier = tier(resource.mimeType, resource.codecs, resource.bitrate)

    fun tier(
        mimeType: String?,
        codecs: String?,
        bitrate: Int?,
    ): Tier {
        val kbps = bitrate?.takeIf { it > 0 }?.div(1000)
        return when (codecOf(mimeType, codecs)) {
            Codec.LOSSLESS -> Tier.LOSSLESS
            Codec.MP3 ->
                when {
                    kbps == null -> Tier.LOW
                    kbps >= 256 -> Tier.HIGH
                    kbps >= 160 -> Tier.AAC_STANDARD
                    else -> Tier.LOW
                }
            Codec.OPUS ->
                when {
                    kbps == null -> Tier.OPUS_STANDARD
                    kbps >= 192 -> Tier.HIGH
                    kbps >= 100 -> Tier.OPUS_STANDARD
                    else -> Tier.LOW
                }
            Codec.AAC ->
                when {
                    kbps == null -> Tier.LOW
                    kbps >= 192 -> Tier.HIGH
                    kbps >= 150 -> Tier.AAC_STANDARD
                    else -> Tier.LOW
                }
            null -> Tier.UNKNOWN
        }
    }

    /** Tier first, then bitrate; higher is better. */
    fun score(resource: PlaybackResource): Int =
        tier(resource).ordinal * 10_000 + (resource.bitrate?.div(1000) ?: 0).coerceIn(0, 9_999)

    private fun codecOf(
        mimeType: String?,
        codecs: String?,
    ): Codec? {
        val codec = codecs.orEmpty().lowercase()
        val mime =
            mimeType
                .orEmpty()
                .substringBefore(';')
                .trim()
                .lowercase()
        return when {
            "flac" in codec || "alac" in codec -> Codec.LOSSLESS
            "opus" in codec -> Codec.OPUS
            "mp3" in codec || codec in mp3Mp4Codecs -> Codec.MP3
            "mp4a" in codec || "aac" in codec -> Codec.AAC
            mime == "audio/flac" || mime == "audio/x-flac" -> Codec.LOSSLESS
            mime == "audio/webm" || mime == "audio/ogg" || mime == "audio/opus" -> Codec.OPUS
            mime == "audio/mpeg" || mime == "audio/mp3" -> Codec.MP3
            mime == "audio/mp4" || mime == "audio/aac" || mime == "audio/x-m4a" || mime == "audio/mp4a-latm" -> Codec.AAC
            else -> null
        }
    }

    // MP3 carried in MP4 uses these object type indications instead of the "mp3" codec name.
    private val mp3Mp4Codecs = setOf("mp4a.6b", "mp4a.69", "mp4a.40.34")
}
