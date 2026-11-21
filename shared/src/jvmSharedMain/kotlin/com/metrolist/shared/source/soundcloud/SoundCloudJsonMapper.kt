package com.metrolist.shared.source.soundcloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Maps SoundCloud api-v2 JSON to the client's models. */
internal object SoundCloudJsonMapper {
    private val json = Json { ignoreUnknownKeys = true }

    // SoundCloud serves square artwork in fixed sizes selected by the file-name suffix.
    // t500x500 is the largest square variant that is never upscaled.
    private val artworkSizeSuffix = Regex("-(?:large|t\\d+x\\d+|crop|mini|tiny|small|badge)(\\.[a-z]+)$")

    fun parseObject(body: String): JsonObject =
        json.parseToJsonElement(body) as? JsonObject
            ?: throw SoundCloudException.ExtractionFailed("Expected a JSON object from SoundCloud")

    fun parseArray(body: String): JsonArray =
        json.parseToJsonElement(body) as? JsonArray
            ?: throw SoundCloudException.ExtractionFailed("Expected a JSON array from SoundCloud")

    fun kind(obj: JsonObject): String? = obj.string("kind")

    /** Returns null for the id-only stubs that playlists use beyond their first few tracks. */
    fun track(obj: JsonObject): SoundCloudTrack? {
        val id = obj.long("id") ?: return null
        val title = obj.string("title") ?: return null
        val permalink = obj.string("permalink_url") ?: return null
        val user = obj.obj("user")
        return SoundCloudTrack(
            id = SOUNDCLOUD_ID_PREFIX + id,
            numericId = id,
            title = title,
            artist = user?.string("username").orEmpty(),
            artistUrl = user?.string("permalink_url")?.let(::https),
            durationMs = obj.long("full_duration") ?: obj.long("duration") ?: 0L,
            artworkUrl = largestArtwork(obj.string("artwork_url") ?: user?.string("avatar_url")),
            permalinkUrl = https(permalink),
            album = obj.obj("publisher_metadata")?.string("album_title")?.takeIf { it.isNotBlank() },
            policy = SoundCloudPolicy.from(obj.string("policy")),
            streamable = obj.bool("streamable") ?: true,
        )
    }

    fun playlistSummary(obj: JsonObject): SoundCloudPlaylistSummary? {
        val id = obj.long("id") ?: return null
        val title = obj.string("title") ?: return null
        val permalink = obj.string("permalink_url") ?: return null
        val user = obj.obj("user")
        val artwork =
            obj.string("artwork_url")
                ?: obj.array("tracks")?.firstNotNullOfOrNull { (it as? JsonObject)?.string("artwork_url") }
                ?: user?.string("avatar_url")
        return SoundCloudPlaylistSummary(
            numericId = id,
            title = title,
            artist = user?.string("username").orEmpty(),
            artworkUrl = largestArtwork(artwork),
            permalinkUrl = https(permalink),
            trackCount = obj.long("track_count")?.toInt() ?: 0,
            isAlbum = obj.bool("is_album") ?: false,
        )
    }

    fun user(obj: JsonObject): SoundCloudUser? {
        val id = obj.long("id") ?: return null
        val permalink = obj.string("permalink_url") ?: return null
        return SoundCloudUser(
            numericId = id,
            name = obj.string("username").orEmpty(),
            avatarUrl = largestArtwork(obj.string("avatar_url")),
            permalinkUrl = https(permalink),
            followers = obj.long("followers_count") ?: 0L,
        )
    }

    fun searchPage(
        body: String,
        filter: SoundCloudSearchFilter,
    ): SoundCloudPage<SoundCloudSearchItem> {
        val root = parseObject(body)
        val items =
            root.array("collection").orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                when (filter) {
                    SoundCloudSearchFilter.TRACKS -> track(obj)?.let(SoundCloudSearchItem::Track)
                    SoundCloudSearchFilter.PLAYLISTS -> playlistSummary(obj)?.let(SoundCloudSearchItem::Playlist)
                    SoundCloudSearchFilter.USERS -> user(obj)?.let(SoundCloudSearchItem::User)
                }
            }
        return SoundCloudPage(items, nextHref(root, items.isEmpty()))
    }

    fun chartsPage(body: String): SoundCloudPage<SoundCloudTrack> {
        val root = parseObject(body)
        val tracks =
            root.array("collection").orEmpty().mapNotNull { entry ->
                ((entry as? JsonObject)?.obj("track"))?.let(::track)
            }
        return SoundCloudPage(tracks, nextHref(root, tracks.isEmpty()))
    }

    /** Ids of every playlist entry, in playlist order, and the entries that already carry full metadata. */
    fun playlistEntries(playlist: JsonObject): Pair<List<Long>, Map<Long, SoundCloudTrack>> {
        val ids = mutableListOf<Long>()
        val full = mutableMapOf<Long, SoundCloudTrack>()
        playlist.array("tracks").orEmpty().forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val id = obj.long("id") ?: return@forEach
            ids += id
            track(obj)?.let { full[id] = it }
        }
        return ids to full
    }

    fun largestArtwork(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return https(artworkSizeSuffix.replace(url, "-t500x500$1"))
    }

    // An empty page with a next link would make callers loop forever.
    private fun nextHref(
        root: JsonObject,
        empty: Boolean,
    ): String? = if (empty) null else root.string("next_href")?.takeIf { it.isNotBlank() }

    private fun https(url: String): String = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = primitive(key)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
}
