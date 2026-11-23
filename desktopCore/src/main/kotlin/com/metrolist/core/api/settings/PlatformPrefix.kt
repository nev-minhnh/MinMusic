package com.metrolist.core.api.settings

/**
 * Which platform a setting belongs to (`SettingEntry.platform`). The UI hides `android`.
 *
 * Same rules as the mock generator (`gen-settings-schema.mjs`), plus the brief's prefixes: `android*` and `Automotive*`
 * are Android; `ios*` is hidden too (the protocol has no `ios` value, so it maps to `android`); `desktop*` and
 * `minimizeToTrayOnClose` are desktop; everything else is `all`.
 */
object PlatformPrefix {
    private val android =
        listOf(
            Regex("^android", RegexOption.IGNORE_CASE),
            Regex("^Automotive"),
            Regex("^ios", RegexOption.IGNORE_CASE),
            Regex("^keepScreenOn"),
            Regex("^enableHaptic"),
            Regex("^enableLandscape"),
            Regex("^blockScreenshots$"),
            Regex("^voiceSearch"),
            Regex("^stopMusicOnTaskClear$"),
            Regex("^(forceBottomNavBar|slimNavBar|floatingNavBar|floatingTopBar|showBottomNav\w+)$"),
            Regex("^swipe"),
            Regex("^appIcon$"),
            Regex("^materialYou"),
            Regex("^matugen"),
            Regex("^densityScale"),
            Regex("^disableAnimatedArtworkOnMobileData$"),
            Regex("^enableSystemWindowFrame$"),
            Regex("^disableWaylandScaleFix$"),
            Regex("^castEnabled$"),
            Regex("^dynamicTheme$"),
            Regex("^autoPureBlack$"),
            Regex("^miniplayerFocusMode$"),
            Regex("^expressiveMiniPlayer"),
            Regex("^showExpressiveMiniPlayer"),
            Regex("^miniPlayerBackgroundStyle$"),
            Regex("^showMiniPlayer(Like|Playlist|Subscribe)Button$"),
            Regex("^showPlayerCastButton$"),
            Regex("^useRoundedRectPlayButton$"),
        )
    private val desktop = listOf(Regex("^desktop"), Regex("^minimizeToTrayOnClose$"))

    fun platformOf(key: String): String =
        when {
            android.any { it.containsMatchIn(key) } -> "android"
            desktop.any { it.containsMatchIn(key) } -> "desktop"
            else -> "all"
        }
}
