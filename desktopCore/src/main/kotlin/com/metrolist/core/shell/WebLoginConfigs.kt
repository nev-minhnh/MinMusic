package com.metrolist.core.shell

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The login windows the core can ask the shell for. Done semantics (verified in the shell, `weblogin/done.rs`): `navPrefix`
 * alone, OR (any listed cookie present with a value AND `script` truthy); with no `script`, cookies alone suffice.
 */
object WebLoginConfigs {
    const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    const val YOUTUBE_LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com"

    /** Done when YouTube Music is loaded with a visitor id, a data sync id and a session index (the account is chosen). */
    const val YOUTUBE_DONE_SCRIPT =
        "(()=>{const c=(window.ytcfg&&ytcfg.data_)||(window.yt&&yt.config_)||{};" +
            "return location.host===\"music.youtube.com\"&&c.VISITOR_DATA&&c.DATASYNC_ID&&c.SESSION_INDEX!==undefined" +
            "?{visitorData:c.VISITOR_DATA,dataSyncId:String(c.DATASYNC_ID).split(\"||\")[0],authUser:String(c.SESSION_INDEX)}:null})()"

    const val DISCORD_CALLBACK_PREFIX = "metrolist-auth://discord/callback"

    /** YouTube omits the user agent (WebView2 default); Google rejects some spoofed agents. */
    fun youtube(url: String = YOUTUBE_LOGIN_URL): WebLoginConfig =
        WebLoginConfig(
            url = url,
            allowedHosts = listOf("music.youtube.com", "www.youtube.com", "accounts.google.com", "google.com", "*.google.com"),
            title = "YouTube Music",
            windowW = 480,
            windowH = 680,
            done =
                WebLoginDone(
                    cookieNames = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID"),
                    cookieUrls = listOf("https://music.youtube.com"),
                    script = YOUTUBE_DONE_SCRIPT,
                ),
            collect = WebLoginCollect(cookieUrls = listOf("https://music.youtube.com", "https://www.youtube.com")),
        )

    fun zing(): WebLoginConfig =
        WebLoginConfig(
            url = "https://zingmp3.vn/",
            allowedHosts = listOf("zingmp3.vn", "*.zingmp3.vn", "id.zalo.me", "*.zalo.me", "*.vng.com.vn"),
            title = "Zing MP3",
            windowW = 560,
            windowH = 760,
            done = WebLoginDone(cookieNames = listOf("zmp3_sid", "zpsid"), cookieUrls = listOf("https://zingmp3.vn")),
            collect = WebLoginCollect(cookieUrls = listOf("https://zingmp3.vn")),
        )

    fun discord(authorizeUrl: String): WebLoginConfig =
        WebLoginConfig(
            url = authorizeUrl,
            allowedHosts = listOf("discord.com"),
            userAgent = CHROME_UA,
            title = "Discord",
            windowW = 520,
            windowH = 720,
            done = WebLoginDone(navPrefix = DISCORD_CALLBACK_PREFIX),
        )

    // <M4-4a>
    // </M4-4a>
}

/** The YouTube done script's result (`{visitorData, dataSyncId, authUser}`, all strings). */
data class YoutubeLoginData(
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String,
) {
    companion object {
        fun from(scriptResult: JsonElement?): YoutubeLoginData? {
            val obj = scriptResult as? JsonObject ?: return null

            fun text(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val visitor = text("visitorData")?.takeIf { it.isNotBlank() } ?: return null
            val sync = text("dataSyncId") ?: return null
            val user = text("authUser") ?: return null
            return YoutubeLoginData(visitor, sync, user)
        }
    }
}
