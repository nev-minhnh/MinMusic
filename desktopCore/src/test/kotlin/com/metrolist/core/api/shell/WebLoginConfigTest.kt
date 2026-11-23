package com.metrolist.core.api.shell

import com.metrolist.core.shell.WebLoginConfigs
import com.metrolist.core.shell.WebLoginResult
import com.metrolist.core.shell.YoutubeLoginData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The configs serialise to the api.md section 5 `shell.webLogin` params, with the done semantics of the shell. */
class WebLoginConfigTest {
    private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> = element!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun youtubeParamsHaveTheDocumentedShape() {
        val p = WebLoginConfigs.youtube().toParams()
        assertEquals(WebLoginConfigs.YOUTUBE_LOGIN_URL, p["url"]!!.jsonPrimitive.content)
        assertEquals(listOf("music.youtube.com", "www.youtube.com", "accounts.google.com", "google.com", "*.google.com"), strings(p["allowedHosts"]))
        assertNull(p["userAgent"], "YouTube keeps the WebView2 default user agent")
        assertEquals(480, p["window"]!!.jsonObject["w"]!!.jsonPrimitive.int)
        assertEquals(680, p["window"]!!.jsonObject["h"]!!.jsonPrimitive.int)
        assertEquals("false", p["window"]!!.jsonObject["maximizable"]!!.jsonPrimitive.content)
        val cookies = p["done"]!!.jsonObject["cookies"]!!.jsonObject
        assertEquals(listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"), strings(cookies["names"]))
        assertEquals(listOf("https://music.youtube.com"), strings(cookies["urls"]))
        assertEquals(WebLoginConfigs.YOUTUBE_DONE_SCRIPT, p["done"]!!.jsonObject["script"]!!.jsonPrimitive.content)
        assertNull(p["done"]!!.jsonObject["navPrefix"])
        assertEquals(listOf("https://music.youtube.com", "https://www.youtube.com"), strings(p["collect"]!!.jsonObject["cookieUrls"]))
        assertNull(p["timeoutMs"], "no timeout: the user may take as long as needed")
    }

    @Test
    fun youtubeScriptIsOneLineWithoutRawNewlines() {
        assertFalse('\n' in WebLoginConfigs.YOUTUBE_DONE_SCRIPT)
        assertTrue(WebLoginConfigs.YOUTUBE_DONE_SCRIPT.startsWith("(()=>{"))
        assertTrue("\"||\"" in WebLoginConfigs.YOUTUBE_DONE_SCRIPT)
    }

    @Test
    fun youtubeCanLogInThroughAChannelSigninUrl() {
        val p = WebLoginConfigs.youtube(url = "https://music.youtube.com/signin?x=1").toParams()
        assertEquals("https://music.youtube.com/signin?x=1", p["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun zingNeedsOnlyACookie() {
        val p = WebLoginConfigs.zing().toParams()
        assertEquals("https://zingmp3.vn/", p["url"]!!.jsonPrimitive.content)
        assertEquals(listOf("zingmp3.vn", "*.zingmp3.vn", "id.zalo.me", "*.zalo.me", "*.vng.com.vn"), strings(p["allowedHosts"]))
        val done = p["done"]!!.jsonObject
        assertEquals(listOf("zmp3_sid", "zpsid"), strings(done["cookies"]!!.jsonObject["names"]))
        assertEquals(listOf("https://zingmp3.vn"), strings(done["cookies"]!!.jsonObject["urls"]))
        assertNull(done["script"], "with no script the cookies alone finish the login")
        assertNull(p["userAgent"])
        assertEquals(560, p["window"]!!.jsonObject["w"]!!.jsonPrimitive.int)
        assertEquals(760, p["window"]!!.jsonObject["h"]!!.jsonPrimitive.int)
    }

    @Test
    fun discordIsDoneByNavigationAlone() {
        val p = WebLoginConfigs.discord("https://discord.com/oauth2/authorize?x=1").toParams()
        assertEquals("https://discord.com/oauth2/authorize?x=1", p["url"]!!.jsonPrimitive.content)
        assertEquals(listOf("discord.com"), strings(p["allowedHosts"]))
        assertEquals(WebLoginConfigs.CHROME_UA, p["userAgent"]!!.jsonPrimitive.content)
        val done = p["done"]!!.jsonObject
        assertEquals("metrolist-auth://discord/callback", done["navPrefix"]!!.jsonPrimitive.content)
        assertNull(done["cookies"])
        assertNull(done["script"])
    }

    @Test
    fun youtubeLoginDataParsesTheScriptResult() {
        val ok =
            buildJsonObject {
                put("visitorData", "vd1")
                put("dataSyncId", "ds1")
                put("authUser", "0")
            }
        assertEquals(YoutubeLoginData("vd1", "ds1", "0"), YoutubeLoginData.from(ok))
        assertNull(YoutubeLoginData.from(null))
        assertNull(YoutubeLoginData.from(JsonNull))
        assertNull(YoutubeLoginData.from(buildJsonObject { put("visitorData", "") ; put("dataSyncId", "x"); put("authUser", "0") }))
        assertNull(YoutubeLoginData.from(buildJsonObject { put("visitorData", "vd") }))
    }

    @Test
    fun resultParsesCancelledAndCookies() {
        val cancelled = WebLoginResult.parse(buildJsonObject { put("cancelled", true) })
        assertTrue(cancelled.cancelled)
        assertNull(cancelled.header())

        val done =
            WebLoginResult.parse(
                buildJsonObject {
                    put("cookies", buildJsonObject { put("a", "1"); put("b", "2") })
                    put("finalUrl", "metrolist-auth://discord/callback?code=c&state=s")
                    put("scriptResult", JsonArray(emptyList()))
                },
            )
        assertFalse(done.cancelled)
        assertEquals("a=1; b=2", done.header())
        assertNotNull(done.finalUrl)
        assertEquals(mapOf("a" to "1", "b" to "2"), done.cookies)

        val withHeader = WebLoginResult.parse(buildJsonObject { put("cookieHeader", "x=y"); put("cookies", buildJsonObject { put("a", "1") }) })
        assertEquals("x=y", withHeader.header())
        assertEquals(JsonObject(emptyMap()).size, WebLoginResult.parse(JsonPrimitive("junk")).cookies.size)
    }

    @Test
    fun resultToStringHidesSecrets() {
        val result = WebLoginResult.parse(buildJsonObject { put("cookieHeader", "SAPISID=topsecret"); put("cookies", buildJsonObject { put("SAPISID", "topsecret") }) })
        assertFalse("topsecret" in result.toString())
    }
}
