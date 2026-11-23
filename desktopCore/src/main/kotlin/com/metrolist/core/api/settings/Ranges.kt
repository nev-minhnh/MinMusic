package com.metrolist.core.api.settings

/** `min`, `max`, `step` of a numeric setting. */
data class Range(
    val min: Double,
    val max: Double,
    val step: Double,
)

/**
 * Bounds for the slider and stepper keys the settings layout (`ui/src/pages/Settings/layout.ts`) shows.
 * Numeric keys that are not listed have no range. Same values as the mock (`gen-settings-schema.mjs`).
 */
object Ranges {
    val table: Map<String, Range> =
        mapOf(
            "seekDurationSeconds" to Range(1.0, 60.0, 1.0),
            "crossfadeDuration" to Range(1.0, 12.0, 1.0),
            "minimumSongDurationSeconds" to Range(0.0, 600.0, 5.0),
            "historyDuration" to Range(1.0, 365.0, 1.0),
            "lyricsTextSize" to Range(16.0, 72.0, 1.0),
            "lyricsLineSpacing" to Range(1.0, 2.0, 0.05),
            "subtitleTextSize" to Range(10.0, 32.0, 1.0),
            "globalLyricsOffsetMs" to Range(-2000.0, 2000.0, 50.0),
            "playbackSpeed" to Range(0.25, 2.0, 0.05),
            "playbackPitch" to Range(0.5, 2.0, 0.05),
            "sleepTimerDefaultMinutes" to Range(1.0, 180.0, 1.0),
            "libraryGridColumns" to Range(1.0, 8.0, 1.0),
            "sliderTrackThicknessDp" to Range(1.0, 12.0, 1.0),
            "volume" to Range(0.0, 1.0, 0.01),
            "previousVolume" to Range(0.0, 1.0, 0.01),
            "maxSongCacheSize" to Range(0.0, 65536.0, 128.0),
            "maxImageCacheSize" to Range(0.0, 8192.0, 64.0),
        )
}
