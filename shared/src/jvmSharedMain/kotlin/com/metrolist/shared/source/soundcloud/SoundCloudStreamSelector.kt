package com.metrolist.shared.source.soundcloud

/** One unencrypted transcoding offered for a track, as resolved by the extractor. */
internal data class SoundCloudStreamCandidate(
    val preset: String,
    val url: String,
    val isHls: Boolean,
)

internal object SoundCloudStreamSelector {
    const val HLS_MIME_TYPE = "application/x-mpegURL"

    /** SoundCloud stream URLs are signed for a short time; refresh comfortably before the ~5 minute limit. */
    const val STREAM_URL_LIFETIME_MS = 4 * 60 * 1000L

    private enum class Format(
        val codec: String,
        val bitrateKbps: Int,
        val progressiveMimeType: String,
        val prefersHls: Boolean,
    ) {
        AAC_160("aac", 160, "audio/mp4", prefersHls = true),
        AAC_96("aac", 96, "audio/mp4", prefersHls = true),
        OPUS("opus", 64, "audio/ogg", prefersHls = false),
        MP3("mp3", 128, "audio/mpeg", prefersHls = false),
    }

    private fun formatOf(preset: String): Format? =
        when {
            preset.contains("aac_160k") -> Format.AAC_160
            preset.contains("aac_96k") -> Format.AAC_96
            preset.contains("opus") -> Format.OPUS
            preset.contains("mp3") -> Format.MP3
            else -> null
        }

    /**
     * Picks AAC 160k, then AAC 96k (both HLS), then Opus and MP3, preferring progressive delivery for
     * the legacy codecs. Unknown presets, such as the adaptive `abr_*` playlists, are ignored.
     */
    fun select(
        candidates: List<SoundCloudStreamCandidate>,
        nowMs: Long,
    ): SoundCloudStream? {
        val best =
            candidates
                .filter { it.url.isNotBlank() }
                .mapNotNull { candidate -> formatOf(candidate.preset)?.let { candidate to it } }
                .minWithOrNull(
                    compareBy<Pair<SoundCloudStreamCandidate, Format>> { it.second.ordinal }
                        .thenBy { (candidate, format) -> candidate.isHls != format.prefersHls },
                ) ?: return null
        val (candidate, format) = best
        return SoundCloudStream(
            url = candidate.url,
            mimeType = if (candidate.isHls) HLS_MIME_TYPE else format.progressiveMimeType,
            bitrateKbps = format.bitrateKbps,
            codec = format.codec,
            isHls = candidate.isHls,
            expiresAtMs = nowMs + STREAM_URL_LIFETIME_MS,
            preset = candidate.preset,
        )
    }
}
