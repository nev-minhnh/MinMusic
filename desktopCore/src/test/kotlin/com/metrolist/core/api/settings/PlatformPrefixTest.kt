package com.metrolist.core.api.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformPrefixTest {
    @Test
    fun androidAndAutomotivePrefixes() {
        assertEquals("android", PlatformPrefix.platformOf("androidCustomFontFile"))
        assertEquals("android", PlatformPrefix.platformOf("AutomotiveFoo"))
        assertEquals("android", PlatformPrefix.platformOf("keepScreenOnMode"))
        assertEquals("android", PlatformPrefix.platformOf("enableSystemWindowFrame"))
        assertEquals("android", PlatformPrefix.platformOf("materialYouEnabled"))
        assertEquals("android", PlatformPrefix.platformOf("showMiniPlayerLikeButton"))
    }

    @Test
    fun iosIsHiddenLikeAndroid() {
        assertEquals("android", PlatformPrefix.platformOf("iosOnboardingComplete"))
    }

    @Test
    fun desktopKeys() {
        assertEquals("desktop", PlatformPrefix.platformOf("desktopSidebarDocked"))
        assertEquals("desktop", PlatformPrefix.platformOf("minimizeToTrayOnClose"))
    }

    @Test
    fun everythingElseIsAll() {
        assertEquals("all", PlatformPrefix.platformOf("themeMode"))
        assertEquals("all", PlatformPrefix.platformOf("hideExplicit"))
        assertEquals("all", PlatformPrefix.platformOf("w2FirstRunDone"))
        assertEquals("all", PlatformPrefix.platformOf("lyricsAiApiKey"))
        assertEquals("all", PlatformPrefix.platformOf("showMiniplayer"))
    }
}
