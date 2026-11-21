package com.metrolist.shared.source.nct

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/** Maps graph.nct.vn Android-app JSON to NCT models. */
internal object NctParser {
    private const val CODE_SIGNED_OUT = 212
    private const val CODE_NOT_FOUND = 224

    class Envelope(
        val data: JsonElement?,
        val total: Int,
        val hasMore: Boolean,
    )

    /** Unwraps `{code, msg, success, data, total, loadmore}`; a non-JSON body means a WAF or block page. */
    fun envelope(
        body: String,
        httpCode: Int,
    ): Envelope {
        val root =
            try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: throw NctException.Blocked(httpCode)
        val code = root.int("code")
        if (code != 0 || root.bool("success") == false) {
            throw when (code) {
                CODE_NOT_FOUND -> NctException.NotFound(code)
                CODE_SIGNED_OUT -> NctException.AuthFailed(code)
                else -> NctException.Unexpected(code)
            }
        }
        return Envelope(
            data = root["data"]?.takeUnless { it is JsonNull },
            total = root.int("total") ?: 0,
            hasMore = root.bool("loadmore") ?: false,
        )
    }

    fun song(element: JsonElement?): NctSong? {
        val obj = element as? JsonObject ?: return null
        val key = obj.string("key") ?: return null
        val artists =
            obj.array("artist").mapNotNull { artist ->
                val a = artist as? JsonObject ?: return@mapNotNull null
                val name = a.string("name") ?: return@mapNotNull null
                NctArtist(key = a.string("key").orEmpty(), name = name, thumbnailUrl = a.string("image"))
            }
        val fallbackArtists =
            obj.string("artistName")?.let { names ->
                listOf(NctArtist(key = obj.string("artistId").orEmpty(), name = names, thumbnailUrl = obj.string("artistImage")))
            }.orEmpty()
        val album =
            (obj.array("album").firstOrNull() as? JsonObject)?.let { a ->
                val albumKey = a.string("key") ?: return@let null
                NctAlbum(key = albumKey, title = a.string("name").orEmpty(), artworkUrl = a.string("image"))
            }
        return NctSong(
            id = NCT_ID_PREFIX + key,
            key = key,
            title = obj.string("name").orEmpty(),
            artists = artists.ifEmpty { fallbackArtists },
            album = album,
            durationMs = (obj.long("duration") ?: 0L) * 1000L,
            artworkUrl = obj.string("image"),
            largeArtworkUrl = obj.string("bgImage") ?: obj.string("image"),
            streams = obj.array("streamURL").mapNotNull { stream(it) },
        )
    }

    fun songs(element: JsonElement?): List<NctSong> = (element as? JsonArray).orEmpty().mapNotNull { song(it) }

    private fun stream(element: JsonElement): NctStream? {
        val obj = element as? JsonObject ?: return null
        val url = obj.string("stream") ?: return null
        val quality = obj.string("type") ?: obj.string("typeUI").orEmpty()
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val extension =
            httpUrl.pathSegments
                .lastOrNull()
                ?.substringAfterLast('.', "")
                ?.lowercase()
        val (mime, codec) =
            when (extension) {
                "mp3" -> "audio/mpeg" to "mp3"
                "flac" -> "audio/flac" to "flac"
                "aac" -> "audio/aac" to "aac"
                "m4a", "mp4" -> "audio/mp4" to "aac"
                else -> null to null
            }
        return NctStream(
            quality = quality,
            url = url,
            onlyVip = obj.bool("onlyVIP") ?: false,
            available = (obj.int("status") ?: 1) == 1,
            mimeType = mime,
            codec = codec,
            bitrate = quality.toIntOrNull()?.times(1000),
            expiresAtMs = httpUrl.queryParameter("e")?.toLongOrNull()?.times(1000L),
        )
    }

    /**
     * Free sessions get the best stream that is not VIP-only and that the server marked playable.
     * VIP sessions get the best stream overall. Lossless ranks above every numeric bitrate.
     */
    fun selectStream(
        streams: List<NctStream>,
        vip: Boolean,
    ): NctStream {
        if (streams.isEmpty()) throw NctException.NotFound(null)
        val allowed = if (vip) streams else streams.filter { !it.onlyVip && it.available }
        return allowed.maxByOrNull { rank(it) } ?: throw NctException.VipRequired()
    }

    private fun rank(stream: NctStream): Int =
        when {
            stream.quality.equals("lossless", ignoreCase = true) -> Int.MAX_VALUE
            else -> stream.bitrate ?: 0
        }

    fun playlist(
        element: JsonElement?,
        hasMore: Boolean,
    ): NctPlaylist? {
        val obj = element as? JsonObject ?: return null
        val key = obj.string("key") ?: return null
        val songs = songs(obj["listSong"])
        return NctPlaylist(
            id = NCT_ID_PREFIX + key,
            key = key,
            title = obj.string("name").orEmpty(),
            description = obj.string("description"),
            artworkUrl = obj.string("image"),
            author = obj.string("artistName") ?: obj.string("userCreated"),
            isAlbum = obj.bool("isAlbum") ?: false,
            songCount = obj.int("totalSongs") ?: songs.size,
            songs = songs,
            hasMore = hasMore,
        )
    }

    fun chart(element: JsonElement?): NctChart? {
        val obj = element as? JsonObject ?: return null
        val key = obj.string("key") ?: return null
        return NctChart(
            id = NCT_ID_PREFIX + key,
            key = key,
            title = obj.string("title") ?: obj.string("titleDetail") ?: obj.string("name").orEmpty(),
            subtitle = obj.string("name"),
            category = obj.string("category"),
            artworkUrl = obj.string("image"),
        )
    }

    fun charts(element: JsonElement?): List<NctChart> = (element as? JsonArray).orEmpty().mapNotNull { chart(it) }

    fun chartPlaylist(element: JsonElement?): NctPlaylist? {
        val obj = element as? JsonObject ?: return null
        val chart = chart(obj) ?: return null
        val songs = songs(obj["items"])
        return NctPlaylist(
            id = chart.id,
            key = chart.key,
            title = chart.title,
            description = chart.subtitle,
            artworkUrl = chart.artworkUrl,
            author = null,
            isAlbum = false,
            songCount = songs.size,
            songs = songs,
            hasMore = false,
        )
    }

    class LyricDetail(
        val plain: String?,
        val timedLyricUrl: String?,
        val decryptKey: String?,
        val aiGenerated: Boolean,
    )

    fun lyricDetail(element: JsonElement?): LyricDetail? {
        val obj = element as? JsonObject ?: return null
        return LyricDetail(
            plain = obj.string("content")?.let { normalizeLineBreaks(it) }?.takeIf { it.isNotBlank() },
            timedLyricUrl = obj.string("timedLyric"),
            decryptKey = obj.string("keyDecryptLyric"),
            aiGenerated = obj.bool("aiGenerated") ?: false,
        )
    }

    /** Timed lyric files are hex-encoded RC4 ciphertext keyed by `keyDecryptLyric`; plain LRC passes through. */
    fun decodeTimedLyric(
        body: String,
        key: String?,
    ): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) return normalizeLineBreaks(trimmed)
        if (key.isNullOrEmpty() || trimmed.length % 2 != 0 || !trimmed.all { it.isHexDigit() }) return null
        val cipher = ByteArray(trimmed.length / 2) { i -> trimmed.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val text = normalizeLineBreaks(rc4(key.toByteArray(Charsets.UTF_8), cipher).toString(Charsets.UTF_8)).trim()
        return text.takeIf { it.startsWith("[") }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun rc4(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            s[i] = s[j].also { s[j] = s[i] }
        }
        var i = 0
        j = 0
        return ByteArray(data.size) { index ->
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            s[i] = s[j].also { s[j] = s[i] }
            (data[index].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
    }

    private fun normalizeLineBreaks(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** Reads the login response: profile fields plus `accessToken.jwtToken`. */
    fun login(element: JsonElement?): NctToken {
        val obj = element as? JsonObject ?: throw NctException.AuthFailed(null)
        val token =
            (obj["accessToken"] as? JsonObject)?.string("jwtToken")
                ?: throw NctException.AuthFailed(null)
        return NctToken(value = token, expiresAtMs = jwtExpiryMs(token), account = account(obj))
    }

    fun account(element: JsonElement?): NctAccount {
        val obj = element as? JsonObject ?: return NctAccount.LoggedOut
        val vip = obj.bool("isVIP") == true || obj.bool("isPayVip") == true || obj.bool("isBaseVip") == true
        return NctAccount(
            loggedIn = true,
            entitlement = if (vip) NctEntitlement.VIP else NctEntitlement.FREE,
            userId = obj.string("userId"),
            displayName = obj.string("displayName") ?: obj.string("fullName") ?: obj.string("username"),
        )
    }

    private fun jwtExpiryMs(token: String): Long? {
        val payload = token.split('.').getOrNull(1) ?: return null
        return try {
            val json = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
            (Json.parseToJsonElement(json) as? JsonObject)?.long("exp")?.times(1000L)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun hotKeywords(element: JsonElement?): List<String> = (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun searchSongs(envelope: Envelope): List<NctSong> = songs((envelope.data as? JsonObject)?.get("songs"))

    private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive

    private fun JsonObject.string(name: String): String? =
        primitive(name)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(name: String): Int? = primitive(name)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonObject.long(name: String): Long? = primitive(name)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    private fun JsonObject.bool(name: String): Boolean? = primitive(name)?.booleanOrNull

    private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
}
