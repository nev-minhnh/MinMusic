package com.metrolist.core.api.settings

import com.metrolist.shared.constants.DiscordPendingAuthSessionKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthSessionKey
import com.metrolist.shared.constants.NctSourceTokenKey
import com.metrolist.shared.constants.ZingSourceCookieKey

/**
 * Settings that hold credentials or account identity. Their values are never sent to a client (`{set}` only) and
 * `settings.set` refuses them.
 *
 * Copied from the private `secretKeys` in `shared/src/desktopMain/.../PlatformSettings.desktop.kt` (25 names), which is
 * the set the desktop settings store keeps encrypted. Keep the two in step.
 */
val SECRET_KEYS: Set<String> =
    setOf(
        DiscordPendingAuthSessionKey,
        "innerTubeCookie",
        "visitorData",
        "dataSyncId",
        "innerTubeAuthUser",
        "lastFmSession",
        "spotifySession",
        "listenBrainzToken",
        "lyricsAiApiKey",
        "lyricsAiApiKeys",
        LyricsOpenRouterOAuthKey,
        LyricsOpenRouterOAuthSessionKey,
        "lyricsDeepLApiKey",
        "accountName",
        "accountEmail",
        "accountChannelHandle",
        "accountThumbnail",
        "discordAccessToken",
        "discordRefreshToken",
        "listenTogetherDesktopSession",
        "deviceConnectPairedDevices",
        "listenTogetherPairedRoomCode",
        "listenTogetherAutoJoinPairedRoom",
        ZingSourceCookieKey,
        NctSourceTokenKey,
    )
