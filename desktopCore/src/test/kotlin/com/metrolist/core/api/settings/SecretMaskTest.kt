package com.metrolist.core.api.settings

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretMaskTest {
    @Test
    fun twentyFiveSecretKeys() {
        assertEquals(25, SECRET_KEYS.size)
        assertTrue("lyricsAiApiKey" in SECRET_KEYS && "lyricsDeepLApiKey" in SECRET_KEYS && "innerTubeCookie" in SECRET_KEYS)
    }

    @Test
    fun secretsComeBackAsSetOnly() {
        assertEquals(buildJsonObject { put("set", true) }, SettingJson.wire("lyricsAiApiKey", "sk-abc"))
        assertEquals(buildJsonObject { put("set", false) }, SettingJson.wire("lyricsAiApiKey", ""))
        assertEquals(buildJsonObject { put("set", false) }, SettingJson.wire("lyricsAiApiKey", "   "))
        assertEquals(buildJsonObject { put("set", true) }, SettingJson.wire("listenTogetherAutoJoinPairedRoom", true))
        assertFalse(SettingJson.wire("accountEmail", "me@example.com").toString().contains("example"))
    }

    @Test
    fun otherKeysPassThrough() {
        assertEquals(JsonPrimitive("DARK"), SettingJson.wire("themeMode", "DARK"))
        assertEquals(JsonPrimitive(true), SettingJson.wire("hideExplicit", true))
        assertEquals(JsonPrimitive(7L), SettingJson.wire("selectedThemeColor", 7L))
    }
}
