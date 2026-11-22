package com.metrolist.core.api

import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.source.sourceOf
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Wire DTOs, exactly as in api.md section 2 and section 4. Optional TS fields have a null default and are omitted when
 * null (encodeDefaults=false); fields typed `X|null` in the spec have no default and are always written.
 */
val CoreJson =
    Json {
        encodeDefaults = false
        explicitNulls = true
        ignoreUnknownKeys = true
    }

@Serializable
data class Art(
    val src: String,
    val w: Int? = null,
    val h: Int? = null,
    val dominant: String? = null,
)

@Serializable
data class ArtistRef(
    val id: String? = null,
    val name: String,
)

@Serializable
data class AlbumRef(
    val id: String? = null,
    val title: String,
)

@Serializable
data class Quality(
    val label: String,
    val lossless: Boolean,
    val kbps: Int? = null,
)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef? = null,
    val durationMs: Long? = null,
    val art: Art? = null,
    val explicit: Boolean? = null,
    val source: String,
    val quality: Quality? = null,
    val altSources: Int? = null,
    val liked: Boolean? = null,
    val download: String? = null,
    val isVideo: Boolean? = null,
    val playCountText: String? = null,
    val addedAt: Long? = null,
    val setVideoId: String? = null,
    val vip: Boolean? = null,
)

@Serializable
data class NavTarget(
    val route: String,
    val params: JsonObject,
)

@Serializable
data class Shelf(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val layout: String,
    val items: List<JsonObject>,
    val more: NavTarget? = null,
)

@Serializable
data class PlayContextInfo(
    val title: String,
    val nav: NavTarget? = null,
)

@Serializable
data class SleepInfo(
    val deadline: Long? = null,
    val endOfTrack: Boolean,
)

@Serializable
data class OutputInfo(
    val id: String,
    val name: String,
    val kind: String,
)

@Serializable
data class EqSummary(
    val enabled: Boolean,
    val profileName: String,
)

@Serializable
data class RemoteInfo(
    val kind: String,
    val name: String,
)

@Serializable
data class PlayerState(
    val track: Track?,
    val playing: Boolean,
    val buffering: Boolean,
    val reconnecting: Boolean,
    val posMs: Long,
    val durMs: Long,
    val bufMs: Long,
    val at: Long,
    val volume: Double,
    val muted: Boolean,
    val shuffle: Boolean,
    val repeat: String,
    val speed: Double,
    val pitch: Double,
    val context: PlayContextInfo? = null,
    val sleep: SleepInfo? = null,
    val output: OutputInfo? = null,
    val eq: EqSummary,
    val quality: Quality? = null,
    val source: String? = null,
    val canPrev: Boolean,
    val canNext: Boolean,
    val remote: RemoteInfo? = null,
)

fun SongItem.toTrack(
    liked: Boolean? = null,
    download: String? = null,
    addedAt: Long? = null,
): Track =
    Track(
        id = id,
        title = title,
        artists = artists.map { ArtistRef(it.id, it.name) },
        album = album?.let { AlbumRef(it.id, it.name) },
        durationMs = duration?.takeIf { it > 0 }?.times(1000),
        art = (previewThumbnailUrl ?: thumbnail)?.takeIf { it.isNotBlank() }?.let { Art(src = it) },
        explicit = if (isExplicit) true else null,
        source = sourceOf(id).key,
        liked = liked,
        download = download,
        isVideo = if (isVideoSong) true else null,
        playCountText = playCountText,
        addedAt = addedAt ?: libraryAddedAt,
    )

fun Song.toTrack(): Track =
    toSongItem().toTrack(
        liked = song.liked,
        download = if (song.isDownloaded) "downloaded" else "none",
        addedAt = song.inLibrary?.toInstant(TimeZone.UTC)?.toEpochMilliseconds(),
    )

inline fun <reified T> T.toJson(): JsonElement = CoreJson.encodeToJsonElement(this)

/** A Track as an `Item` of the discriminated union (`t:"track"`). */
fun Track.toItem(): JsonObject = withType("track", toJson().jsonObject)

private fun withType(
    type: String,
    body: JsonObject,
): JsonObject =
    buildJsonObject {
        put("t", type)
        body.forEach { (key, value) -> put(key, value) }
    }

private fun artOf(thumbnail: String?): JsonElement? = thumbnail?.takeIf { it.isNotBlank() }?.let { Art(src = it).toJson() }

/** Maps a v1 browse item to an api.md `Item`; unsupported item kinds (podcasts, episodes, browse tiles) map to null. */
fun YTItem.toItem(): JsonObject? =
    when (this) {
        is SongItem -> {
            toTrack().toItem()
        }

        is AlbumItem -> {
            withType(
                "album",
                buildJsonObject {
                    put("id", id)
                    put("title", title)
                    put("artists", JsonArray(artists.map { ArtistRef(it.id, it.name).toJson() }))
                    year?.toIntOrNull()?.let { put("year", it) }
                    artOf(thumbnail)?.let { put("art", it) }
                    put("source", sourceOf(id).key)
                    put(
                        "kind",
                        when {
                            type?.contains("single", ignoreCase = true) == true -> "single"
                            type?.equals("ep", ignoreCase = true) == true -> "ep"
                            else -> "album"
                        },
                    )
                },
            )
        }

        is ArtistItem -> {
            withType(
                "artist",
                buildJsonObject {
                    put("id", id)
                    put("name", title)
                    artOf(thumbnail)?.let { put("art", it) }
                    subscribers?.let { put("subtitle", it) }
                    put("source", sourceOf(id).key)
                },
            )
        }

        is PlaylistItem -> {
            withType(
                "playlist",
                buildJsonObject {
                    put("id", id)
                    put("title", title)
                    (subtitle ?: author)?.let { put("subtitle", it) }
                    artOf(thumbnail)?.let { put("art", it) }
                    put("source", sourceOf(id).key)
                    author?.let { put("owner", it) }
                    put("local", isLocal)
                    songCount?.filter(Char::isDigit)?.toIntOrNull()?.let { put("count", it) }
                },
            )
        }

        else -> {
            null
        }
    }
