package com.metrolist.core.api.settings

import com.metrolist.core.server.ApiException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationTest {
    private fun e(
        type: String,
        key: String = "k",
        enumValues: List<String>? = null,
        range: Range? = null,
    ) = SettingEntry(key, type, JsonNull, enumValues, range, "all")

    private fun bad(
        entry: SettingEntry,
        value: JsonElement,
    ) = assertEquals("bad_request", assertFailsWith<ApiException> { Validation.coerce(entry, value) }.code)

    @Test
    fun booleans() {
        assertEquals(true, Validation.coerce(e("bool"), JsonPrimitive(true)))
        bad(e("bool"), JsonPrimitive("yes"))
        bad(e("bool"), JsonPrimitive("true"))
        bad(e("bool"), JsonPrimitive(1))
    }

    @Test
    fun integersAndRanges() {
        val seek = e("int", "seekDurationSeconds", range = Range(1.0, 60.0, 1.0))
        assertEquals(5L, Validation.coerce(seek, JsonPrimitive(5)))
        assertEquals(5L, Validation.coerce(seek, JsonPrimitive(5.0)))
        bad(seek, JsonPrimitive(61))
        bad(seek, JsonPrimitive(0))
        bad(seek, JsonPrimitive(5.5))
        bad(seek, JsonPrimitive("5"))
    }

    @Test
    fun floatsAcceptIntegers() {
        val f = e("float", range = Range(0.0, 1.0, 0.01))
        assertEquals(1.0, Validation.coerce(f, JsonPrimitive(1)))
        assertEquals(0.5, Validation.coerce(f, JsonPrimitive(0.5)))
        bad(f, JsonPrimitive(1.5))
    }

    @Test
    fun enumsCheckMembership() {
        val t = e("enum", enumValues = listOf("LIGHT", "DARK"))
        assertEquals("DARK", Validation.coerce(t, JsonPrimitive("DARK")))
        bad(t, JsonPrimitive("SEPIA"))
        bad(t, JsonPrimitive(1))
    }

    @Test
    fun stringsJsonAndSecrets() {
        assertEquals("x", Validation.coerce(e("string"), JsonPrimitive("x")))
        assertEquals("[]", Validation.coerce(e("json"), JsonPrimitive("[]")))
        bad(e("string"), JsonPrimitive(3))
        bad(e("secret", "lyricsAiApiKey"), JsonPrimitive("sk-secret"))
    }

    @Test
    fun diffReportsOnlyChangedKeys() {
        val before = mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(true))
        val after = mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(false), "c" to JsonPrimitive("x"))
        assertEquals(mapOf("b" to JsonPrimitive(false), "c" to JsonPrimitive("x")), diffSettings(before, after))
        assertEquals(emptyMap(), diffSettings(before, before))
    }
}
