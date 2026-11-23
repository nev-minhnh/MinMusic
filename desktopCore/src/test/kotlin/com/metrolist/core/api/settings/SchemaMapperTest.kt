package com.metrolist.core.api.settings

import com.metrolist.shared.settings.SettingMeta
import com.metrolist.shared.settings.SettingType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemaMapperTest {
    private fun entry(
        key: String,
        type: SettingType,
        default: Any?,
        enumValues: List<String>? = null,
    ) = SchemaMapper.entryOf(SettingMeta(key, type, default, enumValues))

    @Test
    fun scalarTypesMap() {
        assertEquals("bool", entry("hideExplicit", SettingType.BOOLEAN, false).type)
        assertEquals("int", entry("savedWidth", SettingType.INT, 1280).type)
        assertEquals("int", entry("selectedThemeColor", SettingType.LONG, 0L).type)
        assertEquals("float", entry("playbackSpeed", SettingType.FLOAT, 1f).type)
        assertEquals("string", entry("contentCountry", SettingType.STRING, "SYSTEM").type)
    }

    @Test
    fun enumsKeepOrderAndTheStringDefault() {
        val e = entry("themeMode", SettingType.INT, "DARK", listOf("LIGHT", "DARK", "AUTO", "PURE_BLACK"))
        assertEquals("enum", e.type)
        assertEquals(listOf("LIGHT", "DARK", "AUTO", "PURE_BLACK"), e.enumValues)
        assertEquals(JsonPrimitive("DARK"), e.default)
        assertEquals(listOf("LIGHT", "DARK", "AUTO", "PURE_BLACK"), e.toJson()["enumValues"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun closedStringSetsBecomeEnums() {
        val e = entry("appLanguage", SettingType.STRING, "SYSTEM")
        assertEquals("enum", e.type)
        assertEquals(listOf("SYSTEM", "en", "vi"), e.enumValues)
    }

    @Test
    fun jsonKeysAndSecrets() {
        assertEquals("json", entry("blockedSongsJson", SettingType.STRING, "[]").type)
        val secret = entry("lyricsAiApiKey", SettingType.STRING, "sk-live-default")
        assertEquals("secret", secret.type)
        assertEquals(SettingJson.SECRET_UNSET, secret.default)
        assertNull(secret.enumValues)
    }

    @Test
    fun rangesComeFromTheTableAndOnlyForNumbers() {
        val seek = entry("seekDurationSeconds", SettingType.INT, 10)
        assertEquals(Range(1.0, 60.0, 1.0), seek.range)
        val json = seek.toJson()
        assertEquals(1L, json["min"]!!.jsonPrimitive.content.toLong())
        assertEquals(60L, json["max"]!!.jsonPrimitive.content.toLong())
        assertEquals(0.05, entry("lyricsLineSpacing", SettingType.FLOAT, 1.3f).toJson()["step"]!!.jsonPrimitive.double)
        assertNull(entry("hideExplicit", SettingType.BOOLEAN, false).range)
        assertNull(entry("savedWidth", SettingType.INT, 1280).range)
    }

    @Test
    fun floatsKeepTheirDecimalForm() {
        assertEquals(0.1, entry("volume", SettingType.FLOAT, 0.1f).default.jsonPrimitive.double)
    }

    @Test
    fun entriesAreSortedByKeyAndCarryAPlatform() {
        val list =
            SchemaMapper.entries(
                listOf(
                    SettingMeta("zeta", SettingType.BOOLEAN, true, null),
                    SettingMeta("androidFoo", SettingType.BOOLEAN, true, null),
                    SettingMeta("desktopSidebarDocked", SettingType.BOOLEAN, true, null),
                ),
            )
        assertEquals(listOf("androidFoo", "desktopSidebarDocked", "zeta"), list.map { it.key })
        assertEquals(listOf("android", "desktop", "all"), list.map { it.platform })
        assertEquals(true, list[0].toJson()["default"]!!.jsonPrimitive.boolean)
    }
}
