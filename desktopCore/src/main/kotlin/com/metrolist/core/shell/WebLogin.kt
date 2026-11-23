package com.metrolist.core.shell

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** When the shell may close the login window: `navPrefix` alone, OR (any listed cookie present AND `script` truthy). */
data class WebLoginDone(
    val cookieNames: List<String> = emptyList(),
    val cookieUrls: List<String> = emptyList(),
    val navPrefix: String? = null,
    val script: String? = null,
)

/** What the shell reads when the login is done. */
data class WebLoginCollect(
    val cookieUrls: List<String> = emptyList(),
    val script: String? = null,
)

/** The api.md section 5 `shell.webLogin` params. [timeoutMs] null means no timeout (the user may take as long as needed). */
data class WebLoginConfig(
    val url: String,
    val allowedHosts: List<String>,
    val title: String,
    val windowW: Int,
    val windowH: Int,
    val done: WebLoginDone,
    val collect: WebLoginCollect = WebLoginCollect(),
    val userAgent: String? = null,
    val banner: String? = null,
    val timeoutMs: Long? = null,
) {
    fun toParams(): JsonObject =
        buildJsonObject {
            put("url", url)
            put("allowedHosts", strings(allowedHosts))
            if (userAgent != null) put("userAgent", userAgent)
            put("title", title)
            if (banner != null) put("banner", banner)
            put(
                "window",
                buildJsonObject {
                    put("w", windowW)
                    put("h", windowH)
                    put("maximizable", false)
                },
            )
            put(
                "done",
                buildJsonObject {
                    if (done.cookieNames.isNotEmpty()) {
                        put(
                            "cookies",
                            buildJsonObject {
                                put("names", strings(done.cookieNames))
                                put("urls", strings(done.cookieUrls))
                            },
                        )
                    }
                    if (done.navPrefix != null) put("navPrefix", done.navPrefix)
                    if (done.script != null) put("script", done.script)
                },
            )
            put(
                "collect",
                buildJsonObject {
                    put("cookieUrls", strings(collect.cookieUrls))
                    if (collect.script != null) put("script", collect.script)
                },
            )
            if (timeoutMs != null) put("timeoutMs", timeoutMs)
        }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
}

/**
 * The shell's answer. [cookies] and [cookieHeader] are secrets: they are never logged and only handed to the encrypted
 * stores. [toString] hides them.
 */
class WebLoginResult(
    val cancelled: Boolean,
    val cookies: Map<String, String>,
    val cookieHeader: String?,
    val scriptResult: JsonElement?,
    val finalUrl: String?,
) {
    /** The `Cookie` header: the shell's [cookieHeader], else the [cookies] joined as `name=value; name=value`. Null when empty. */
    fun header(): String? = cookieHeader?.takeIf { it.isNotBlank() } ?: cookies.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    override fun toString(): String = "WebLoginResult(cancelled=$cancelled, cookies=${cookies.size}, hasFinalUrl=${finalUrl != null})"

    companion object {
        /** Pure parse of the `shell.webLogin` result object. */
        fun parse(result: JsonElement): WebLoginResult {
            val obj = result as? JsonObject ?: JsonObject(emptyMap())
            val cancelled = (obj["cancelled"] as? JsonPrimitive)?.content == "true"
            val cookies =
                (obj["cookies"] as? JsonObject)
                    ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name to it } }
                    ?.toMap()
                    .orEmpty()
            val header = (obj["cookieHeader"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val script = obj["scriptResult"]?.takeUnless { it is JsonNull }
            val finalUrl = (obj["finalUrl"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return WebLoginResult(cancelled, cookies, header, script, finalUrl)
        }
    }
}

/** Asks the shell to open a login window (`shell.webLogin`) and waits until it is done or cancelled. */
object WebLogin {
    suspend fun request(cfg: WebLoginConfig): WebLoginResult = WebLoginResult.parse(ShellChannel.request("shell.webLogin", cfg.toParams(), cfg.timeoutMs))
}
