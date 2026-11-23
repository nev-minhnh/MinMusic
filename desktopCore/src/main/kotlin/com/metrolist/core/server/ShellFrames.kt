package com.metrolist.core.server

import com.metrolist.core.shell.ShellChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The server side of the shell channel. CoreServer calls [attach] when a `role=shell` connection finishes `sys.hello`,
 * [offer] for each frame that connection sends, and `ShellChannel.detach` when it closes.
 */
object ShellFrames {
    fun attach(conn: Conn): Any = ShellChannel.attach { frame -> conn.send(frame) }

    /** True when [text] is a reply (`sid`, no `id`) that was handed to [ShellChannel]; false for every other frame. */
    fun offer(text: String): Boolean {
        val frame = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return false
        return isReply(frame) && ShellChannel.complete(frame)
    }

    /** A shell reply carries `sid` and no `id`. */
    internal fun isReply(frame: JsonObject): Boolean = "sid" in frame && "id" !in frame
}

/**
 * What may be logged about a request's params. Methods that carry a secret log `<redacted>`; every other method logs only the
 * key names, never the values.
 */
object ParamRedaction {
    val SENSITIVE_METHODS =
        setOf("source.nct.login", "lastfm.login", "listenbrainz.setToken", "account.youtube.tokenLogin")

    fun describe(
        method: String?,
        params: JsonObject,
    ): String =
        when {
            method in SENSITIVE_METHODS -> "<redacted>"
            params.isEmpty() -> "{}"
            else -> params.keys.sorted().joinToString(prefix = "{", postfix = "}", separator = ",")
        }
}
