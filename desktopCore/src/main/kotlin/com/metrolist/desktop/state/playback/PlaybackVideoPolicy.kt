package com.metrolist.desktop.state.playback

import com.metrolist.shared.settings.Settings

/**
 * Runtime-only switch over `Settings.musicVideoPlaybackEnabled`. The v2 sidecar has no video surface yet, so it sets
 * [audioOnly] at start instead of writing the setting: the settings file is shared with v1, which must keep its value.
 */
object PlaybackVideoPolicy {
    @Volatile
    var audioOnly: Boolean = false

    val musicVideoPlaybackEnabled: Boolean
        get() = !audioOnly && Settings.shared.musicVideoPlaybackEnabled
}
