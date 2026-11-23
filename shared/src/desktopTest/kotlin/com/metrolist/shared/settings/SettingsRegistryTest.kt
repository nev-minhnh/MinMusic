package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsRegistryTest {
    private fun settings() = Settings(MapSettings())

    private fun meta(
        s: Settings,
        key: String,
    ) = assertNotNull(s.registry.firstOrNull { it.key == key }, "missing $key")

    @Test
    fun registryIsCompleteAfterConstruction() {
        val s = settings()
        assertTrue(s.registry.size >= 300, "only ${s.registry.size} keys")
        assertEquals(s.registry.size, s.registry.map { it.key }.toSet().size, "duplicate keys")
        assertNotNull(s.registry.firstOrNull { it.key == "w2FirstRunDone" })
        assertNotNull(s.registry.firstOrNull { it.key == "appLanguage" })
        assertNotNull(s.registry.firstOrNull { it.key == "minimizeToTrayOnClose" })
    }

    @Test
    fun enumsCarryTheirNamesAndAStringDefault() {
        val s = settings()
        val theme = meta(s, "themeMode")
        assertEquals(listOf("LIGHT", "DARK", "AUTO", "PURE_BLACK"), theme.enumValues)
        assertEquals("DARK", theme.default)
        val enums = s.registry.filter { it.enumValues != null }
        assertTrue(enums.size >= 20, "only ${enums.size} enum keys")
        for (m in enums) {
            assertTrue(m.enumValues!!.isNotEmpty(), m.key)
            assertTrue(m.default in m.enumValues!!, "${m.key} default ${m.default}")
        }
    }

    @Test
    fun defaultsHaveTheTypeOfTheirMeta() {
        for (m in settings().registry) {
            val ok =
                when (m.type) {
                    SettingType.BOOLEAN -> m.default is Boolean
                    SettingType.STRING -> m.default is String
                    SettingType.LONG -> m.default is Long
                    SettingType.FLOAT -> m.default is Float
                    SettingType.INT -> if (m.enumValues != null) m.default is String else m.default is Int
                }
            assertTrue(ok, "${m.key}: ${m.type} default ${m.default}")
        }
    }

    @Test
    fun getRawReturnsTheDefaultsAndSetRawRoundTrips() {
        val backing = MapSettings()
        val s = Settings(backing)
        // Keys the constructor's migrations wrote are not at their default; appLanguage is canonicalised.
        for (m in s.registry) if (m.key !in backing.keys && m.key != "appLanguage") assertEquals(m.default, s.getRaw(m.key), "default of ${m.key}")

        s.setRaw("hideExplicit", true)
        assertEquals(true, s.getRaw("hideExplicit"))
        assertTrue(s.hideExplicit)

        s.setRaw("themeMode", "PURE_BLACK")
        assertEquals("PURE_BLACK", s.getRaw("themeMode"))
        assertEquals(ThemeMode.PURE_BLACK, s.themeMode)

        s.setRaw("w2FirstRunDone", true)
        assertTrue(s.w2FirstRunDone)

        s.setRaw("accountName", "x")
        assertEquals("x", s.getRaw("accountName"))
        s.setRaw("themeMode", "DARK")
        assertEquals(ThemeMode.DARK, s.themeMode)
    }

    @Test
    fun setRawRefreshesAnAlreadyReadProperty() {
        val s = settings()
        assertFalse(s.hideExplicit)
        s.setRaw("hideExplicit", true)
        assertTrue(s.hideExplicit)
        s.setRaw("hideExplicit", false)
        assertFalse(s.hideExplicit)
    }

    @Test
    fun appLanguageGoesThroughTheCanonicalProperty() {
        val s = settings()
        s.setRaw("appLanguage", "vi")
        assertEquals("vi", s.appLanguage)
        assertEquals("vi", s.getRaw("appLanguage"))
    }

    @Test
    fun aTypeMismatchOrUnknownKeyThrows() {
        val s = settings()
        assertFailsWith<IllegalArgumentException> { s.setRaw("hideExplicit", "yes") }
        assertFailsWith<IllegalArgumentException> { s.setRaw("themeMode", "SEPIA") }
        assertFailsWith<IllegalArgumentException> { s.setRaw("themeMode", 1) }
        assertFailsWith<IllegalArgumentException> { s.setRaw("minimumSongDurationSeconds", 1.5) }
        assertFailsWith<IllegalArgumentException> { s.setRaw("minimumSongDurationSeconds", 3_000_000_000L) }
        assertFailsWith<IllegalArgumentException> { s.setRaw("noSuchKey", 1) }
        assertFalse(s.hideExplicit)
    }

    @Test
    fun integerAndLongKeysAcceptWholeNumbers() {
        val s = settings()
        s.setRaw("minimumSongDurationSeconds", 45.0)
        assertEquals(45, s.getRaw("minimumSongDurationSeconds"))
        s.setRaw("selectedThemeColor", 7)
        assertEquals(7L, s.getRaw("selectedThemeColor"))
    }
}
