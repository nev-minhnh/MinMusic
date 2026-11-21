package com.metrolist.shared.source.zing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.util.Locale

class ZingEnvelope(
    val err: Int,
    val msg: String?,
    val data: JsonElement?,
)

/** Maps zingmp3.vn `{err, msg, data}` responses to the Zing models. */
object ZingMapper {
    /** The placeholder Zing puts in a tier that needs a VIP subscription. Such a tier is never used. */
    const val VIP_PLACEHOLDER = "VIP"

    private val json = Json { ignoreUnknownKeys = true }
    private val expiryPattern = Regex("""\bexp=(\d+)""")
    private val artworkSizePattern = Regex("""/w\d+_r1x1_jpeg/""")
    private const val ARTWORK_SIZE = "/w600_r1x1_jpeg/"

    fun envelope(body: String): ZingEnvelope {
        val root =
            try {
                json.parseToJsonElement(body) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: throw ZingException.Network("Unexpected response")
        val err = root.int("err") ?: throw ZingException.Network("Unexpected response")
        return ZingEnvelope(err, root.str("msg"), root["data"])
    }

    fun song(item: JsonObject): ZingSong? {
        val zingId = item.str("encodeId") ?: return null
        val title = item.str("title") ?: return null
        return ZingSong(
            id = ZING_ID_PREFIX + zingId,
            zingId = zingId,
            title = title,
            artists =
                item.array("artists").mapNotNull { artist ->
                    artist.str("name")?.let { ZingArtistRef(artist.str("id"), it, artist.str("alias")) }
                },
            artistsText = item.str("artistsNames"),
            album =
                item.obj("album")?.let { album ->
                    val albumId = album.str("encodeId") ?: return@let null
                    ZingAlbumRef(ZING_ID_PREFIX + albumId, album.str("title") ?: return@let null, artwork(album))
                },
            durationSeconds = item.int("duration"),
            artworkUrl = artwork(item),
            isWorldWide = item.bool("isWorldWide"),
            streamingStatus = item.int("streamingStatus"),
            streamPrivileges = item.ints("streamPrivileges"),
            downloadPrivileges = item.ints("downloadPrivileges"),
            hasLyric = item.bool("hasLyric") == true,
        )
    }

    /** Songs in `data.items`, the list shape shared by search, chart and new-release pages. */
    fun songs(data: JsonObject): List<ZingSong> = data.array("items").mapNotNull(::song)

    /** Tiers of a `song/get/streaming` response that hold a real URL; "VIP" placeholders and missing tiers are left out. */
    fun streams(data: JsonObject): List<ZingStream> =
        ZingQuality.entries.mapNotNull { quality ->
            val url = data.str(quality.key)?.let(::streamUrl) ?: return@mapNotNull null
            ZingStream(url, quality, quality.mimeType, quality.bitrateKbps, expiresAtMs(url))
        }

    /**
     * Best playable tier, lossless > 320 > 128, among tiers that hold a URL. Throws [ZingException.AuthRequired] when
     * the song only has VIP tiers, so the paywall is reported rather than worked around.
     */
    fun selectStream(
        data: JsonObject,
        preferLossless: Boolean,
    ): ZingStream {
        val available = streams(data).filter { preferLossless || it.quality != ZingQuality.LOSSLESS }
        available.minByOrNull { it.quality.ordinal }?.let { return it }
        val locked = ZingQuality.entries.any { data.str(it.key) == VIP_PLACEHOLDER }
        if (locked) throw ZingException.AuthRequired(0, VIP_PLACEHOLDER)
        throw ZingException.NotFound(0, "No stream")
    }

    fun expiresAtMs(url: String): Long? {
        val value = expiryPattern.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return if (value < 100_000_000_000L) value * 1000 else value
    }

    /** `data.file` of `lyric/get/lyric`, accepted only from Zing's own HTTPS CDN. */
    fun lyricFileUrl(data: JsonObject): String? {
        val file = data.str("file") ?: return null
        val uri = runCatching { URI(file) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return file.takeIf { uri.scheme == "https" && (host == "zmdcdn.me" || host.endsWith(".zmdcdn.me")) }
    }

    /** Line-synced LRC built from the word timings in `data.sentences`, for responses without a lyric file. */
    fun lrcFromSentences(data: JsonObject): String? {
        val lines =
            data.array("sentences").mapNotNull { sentence ->
                val words = sentence.array("words")
                val start = words.firstOrNull()?.long("startTime") ?: return@mapNotNull null
                val text = words.mapNotNull { it.str("data") }.joinToString(" ")
                "[${lrcTimestamp(start)}]$text"
            }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun lrcTimestamp(ms: Long): String {
        val centis = ms / 10
        return String.format(Locale.ROOT, "%02d:%02d.%02d", centis / 6000, centis / 100 % 60, centis % 100)
    }

    fun playlistRef(item: JsonObject): ZingPlaylistRef? {
        val zingId = item.str("encodeId") ?: return null
        return ZingPlaylistRef(
            id = ZING_ID_PREFIX + zingId,
            zingId = zingId,
            title = item.str("title") ?: return null,
            artworkUrl = artwork(item),
            artistsText = item.str("artistsNames"),
            isAlbum = item.bool("isAlbum") == true,
        )
    }

    fun playlist(data: JsonObject): ZingPlaylist {
        val ref = playlistRef(data) ?: throw ZingException.NotFound(0, "No playlist")
        val songs = data.obj("song")
        return ZingPlaylist(
            id = ref.id,
            zingId = ref.zingId,
            title = ref.title,
            artworkUrl = ref.artworkUrl,
            artistsText = ref.artistsText,
            description = data.str("sortDescription") ?: data.str("description"),
            isAlbum = ref.isAlbum,
            songs = songs?.array("items").orEmpty().mapNotNull(::song),
            total = songs?.int("total"),
        )
    }

    fun artistSummary(item: JsonObject): ZingArtistSummary? {
        val alias = item.str("alias") ?: return null
        return ZingArtistSummary(
            zingId = item.str("id"),
            alias = alias,
            name = item.str("name") ?: return null,
            artworkUrl = item.str("thumbnailM") ?: item.str("thumbnail"),
            followers = item.long("totalFollow"),
        )
    }

    fun artist(data: JsonObject): ZingArtist {
        val summary = artistSummary(data) ?: throw ZingException.NotFound(0, "No artist")
        val sections = data.array("sections")
        return ZingArtist(
            summary = summary,
            biography = data.str("sortBiography") ?: data.str("biography"),
            topSongs =
                sections
                    .firstOrNull { it.str("sectionType") == "song" }
                    ?.array("items")
                    .orEmpty()
                    .mapNotNull(::song),
            playlists =
                sections
                    .filter { it.str("sectionType") == "playlist" }
                    .flatMap { section -> section.array("items").mapNotNull(::playlistRef) }
                    .distinctBy { it.zingId },
        )
    }

    fun searchResult(
        data: JsonObject,
        type: ZingSearchType,
        page: Int,
        pageSize: Int,
    ): ZingSearchResult {
        val items = data.array("items")
        val total = data.int("total")
        return ZingSearchResult(
            songs = if (type == ZingSearchType.SONG) items.mapNotNull(::song) else emptyList(),
            playlists = if (type == ZingSearchType.PLAYLIST) items.mapNotNull(::playlistRef) else emptyList(),
            artists = if (type == ZingSearchType.ARTIST) items.mapNotNull(::artistSummary) else emptyList(),
            total = total,
            hasMore = total != null && page.toLong() * pageSize < total,
        )
    }

    fun multiSearchResult(data: JsonObject): ZingSearchResult =
        ZingSearchResult(
            songs = data.array("songs").mapNotNull(::song),
            playlists = data.array("playlists").mapNotNull(::playlistRef),
            artists = data.array("artists").mapNotNull(::artistSummary),
            total = null,
            hasMore = false,
        )

    fun weekChart(
        region: String,
        data: JsonObject,
    ): ZingWeekChart =
        ZingWeekChart(
            region = data.str("country") ?: region,
            playlistId = data.str("playlistId"),
            week = data.int("week"),
            year = data.int("year"),
            songs = data.array("items").mapNotNull(::song),
        )

    fun charts(data: JsonObject): ZingCharts =
        ZingCharts(
            realtime = data.obj("RTChart")?.array("items").orEmpty().mapNotNull(::song),
            newReleases = data.array("newRelease").mapNotNull(::song),
            weekly =
                data.obj("weekChart")?.mapNotNull { (region, chart) ->
                    (chart as? JsonObject)?.let { weekChart(region, it) }
                }.orEmpty(),
        )

    fun sections(data: JsonElement): List<ZingSection> =
        (data as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { section ->
            ZingSection(section.str("title"), section.array("items").mapNotNull(::playlistRef))
        }

    /** A guest profile answers `userid: 0`; a logged-in one carries the account id. */
    fun profileUserId(data: JsonObject): String? {
        val raw = (data["userid"] as? JsonPrimitive)?.content ?: return null
        return raw.takeUnless { it.isBlank() || it == "0" || it == "null" }
    }

    fun profileName(data: JsonObject): String? = data.str("name") ?: data.str("displayName") ?: data.str("username")

    private fun artwork(item: JsonObject): String? =
        (item.str("thumbnailM") ?: item.str("thumbnail"))?.replace(artworkSizePattern, ARTWORK_SIZE)

    private fun streamUrl(value: String): String? {
        if (value == VIP_PLACEHOLDER) return null
        val url = if (value.startsWith("//")) "https:$value" else value
        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.str(key: String): String? =
    primitive(key)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull

private fun JsonObject.long(key: String): Long? = primitive(key)?.longOrNull

private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

private fun JsonObject.ints(key: String): List<Int> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
