package com.metrolist.core.api.settings

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The handlers against an in-memory Settings (no sockets). */
class SettingsApiTest {
    @BeforeTest
    fun install() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun remove() = Settings.replaceSharedForTest(null)

    private fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject =
        when (method) {
            "settings.schema" -> SettingsApi.schemaResult()
            "settings.get" -> SettingsApi.getResult(params)
            "settings.set" -> SettingsApi.setResult(params)
            else -> SettingsApi.resetResult(params)
        }

    private fun keys(vararg k: String) = buildJsonObject { put("keys", JsonArray(k.map(::JsonPrimitive))) }

    @Test
    fun schemaListsEveryRegisteredKey() {
        val entries = call("settings.schema")["entries"]!!.jsonArray
        assertTrue(entries.size >= 300, "only ${entries.size}")
        val names = entries.map { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals(names.sorted(), names)
        assertTrue("w2FirstRunDone" in names && "checkForUpdates" in names && "updateChannel" in names)
    }

    @Test
    fun setThenGetThenReset() {
        val values =
            buildJsonObject {
                put("hideExplicit", true)
                put("themeMode", "PURE_BLACK")
            }
        call("settings.set", buildJsonObject { put("values", values) })
        val v = call("settings.get", keys("hideExplicit", "themeMode"))["values"]!!.jsonObject
        assertEquals(JsonPrimitive(true), v["hideExplicit"])
        assertEquals(JsonPrimitive("PURE_BLACK"), v["themeMode"])
        call("settings.reset", keys("hideExplicit"))
        assertFalse(Settings.shared.hideExplicit)
    }

    @Test
    fun aSetEmitsOneChangedEvent() {
        SettingsHub.scan()
        val change =
            runBlocking {
                withTimeout(5_000) {
                    coroutineScope {
                        val waiting = async(start = CoroutineStart.UNDISPATCHED) { SettingsHub.changes.first() }
                        val values =
                            buildJsonObject {
                                put("hideExplicit", true)
                                put("themeMode", "AUTO")
                            }
                        call("settings.set", buildJsonObject { put("values", values) })
                        waiting.await()
                    }
                }
            }
        assertEquals(setOf("hideExplicit", "themeMode"), change.keys)
    }

    @Test
    fun secretsNeverLeak() {
        SettingsApi.setAiKey(Settings.shared, "OpenRouter", "sk-secret")
        assertFalse(call("settings.get").toString().contains("sk-secret"))
        val one = call("settings.get", keys("lyricsAiApiKey"))["values"]!!.jsonObject["lyricsAiApiKey"]!!.jsonObject
        assertEquals(JsonPrimitive(true), one["set"])
    }
}
