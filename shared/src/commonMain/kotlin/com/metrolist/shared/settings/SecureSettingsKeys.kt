package com.metrolist.shared.settings

import com.metrolist.shared.constants.DiscordPendingAuthSessionKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthSessionKey
import com.metrolist.shared.constants.NctSourceTokenKey
import com.metrolist.shared.constants.ZingSourceCookieKey

internal val secureSettingsKeys =
    setOf(
        DiscordPendingAuthSessionKey,
        "innerTubeCookie",
        "visitorData",
        "dataSyncId",
        "innerTubeAuthUser",
        "lastFmSession",
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
        "listenTogetherServerUrl",
        "listenTogetherCustomServers",
        "listenTogetherServerMetadata",
        "deviceConnectPairedDevices",
        "listenTogetherPairedRoomCode",
        "spotifySession",
        ZingSourceCookieKey,
        NctSourceTokenKey,
    )
