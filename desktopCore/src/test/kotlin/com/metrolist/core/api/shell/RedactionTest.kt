package com.metrolist.core.api.shell

import com.metrolist.core.server.ParamRedaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactionTest {
    private val params = JsonObject(mapOf("account" to JsonPrimitive("alice"), "password" to JsonPrimitive("hunter2-secret")))

    @Test
    fun secretMethodsLogNothingAboutTheirParams() {
        for (method in listOf("source.nct.login", "lastfm.login", "listenbrainz.setToken", "account.youtube.tokenLogin")) {
            assertEquals("<redacted>", ParamRedaction.describe(method, params), method)
        }
    }

    @Test
    fun otherMethodsLogOnlyKeyNames() {
        val text = ParamRedaction.describe("queue.add", params)
        assertEquals("{account,password}", text)
        assertFalse("hunter2" in text)
        assertFalse("alice" in text)
        assertEquals("{}", ParamRedaction.describe("sys.ping", JsonObject(emptyMap())))
    }

    @Test
    fun anUnknownMethodNameIsStillSafe() {
        assertFalse("hunter2" in ParamRedaction.describe(null, params))
    }
}
