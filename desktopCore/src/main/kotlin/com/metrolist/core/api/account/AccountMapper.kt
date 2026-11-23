package com.metrolist.core.api.account

import com.metrolist.core.api.jsonOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure mapping of the YouTube account state to api.md 3.11 `account.get`. */
object AccountMapper {
    /** `{signedIn:false}` when signed out, else `{youtube:{name, email?, handle?, avatar?:{src}, channels?}, signedIn:true}`. */
    fun account(
        signedIn: Boolean,
        name: String,
        email: String,
        handle: String,
        thumbnail: String,
        channels: List<Pair<String, String>>? = null,
    ): JsonObject {
        if (!signedIn) return jsonOf("signedIn" to JsonPrimitive(false))
        val youtube =
            jsonOf(
                "name" to JsonPrimitive(name.ifBlank { email.ifBlank { "YouTube" } }),
                "email" to email.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) },
                "handle" to handle.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) },
                "avatar" to thumbnail.takeIf { it.isNotBlank() }?.let { jsonOf("src" to JsonPrimitive(it)) },
                "channels" to
                    channels?.let { list ->
                        JsonArray(list.map { (id, channelName) -> jsonOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(channelName)) })
                    },
            )
        return jsonOf("youtube" to youtube, "signedIn" to JsonPrimitive(true))
    }

    /**
     * Which entry `switchChannel` moves to: the one after the selected entry (wrapping), or the first when none is selected.
     * Null when there is no other channel to switch to.
     */
    fun nextChannelIndex(selected: List<Boolean>): Int? {
        if (selected.size <= 1) return null
        val current = selected.indexOfFirst { it }
        return if (current < 0) 0 else (current + 1) % selected.size
    }

    /** `signinUrl` of an account entry as an absolute URL (v1 `selectWebViewAccount`). Null when blank. */
    fun absoluteSigninUrl(signinUrl: String?): String? {
        val url = signinUrl?.takeIf { it.isNotBlank() } ?: return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> "https://music.youtube.com$url"
            else -> "https://music.youtube.com/$url"
        }
    }
}
