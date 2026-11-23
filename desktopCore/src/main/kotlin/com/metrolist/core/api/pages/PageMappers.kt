package com.metrolist.core.api.pages

import com.metrolist.core.api.AlbumRef
import com.metrolist.core.api.Art
import com.metrolist.core.api.ArtistCard
import com.metrolist.core.api.PlaylistCard
import com.metrolist.core.api.Shelf
import com.metrolist.core.api.Track
import com.metrolist.core.api.cardJson
import com.metrolist.core.api.json
import com.metrolist.core.api.jsonOf
import com.metrolist.core.api.merged
import com.metrolist.core.api.slug
import com.metrolist.core.api.toCard
import com.metrolist.core.api.toItems
import com.metrolist.core.api.toJson
import com.metrolist.core.api.toTrack
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.source.sourceOf
import com.metrolist.shared.viewmodels.LastFmArtistData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Pure mapping of v1 page models to the api.md 3.5 results (no network, no database). */
object PageMappers {
    fun trackJson(track: Track): JsonObject = track.toJson().jsonObject

    fun tracksJson(songs: List<SongItem>): JsonArray = JsonArray(songs.map { trackJson(it.toTrack()) })

    private fun cursorJson(next: String?): JsonElement = next?.let { JsonPrimitive(it) } ?: JsonNull

    // --- album.get -----------------------------------------------------------------------------------------------

    fun albumResult(
        page: AlbumPage,
        saved: Boolean = false,
    ): JsonObject {
        val card = page.album.toCard()
        val albumRef = AlbumRef(page.album.id, page.album.title)
        val summed = page.songs.sumOf { it.duration?.takeIf { seconds -> seconds > 0 } ?: 0L } * 1000
        val durationMs = if (summed > 0) summed else (page.album.duration ?: 0L) * 1000
        val year = card.year ?: page.songs.firstNotNullOfOrNull { it.year } ?: 0
        val album =
            card.toJson().jsonObject.merged(
                jsonOf(
                    "description" to page.description.json(),
                    "durationMs" to JsonPrimitive(durationMs),
                    "trackCount" to JsonPrimitive(page.songs.size),
                    "year" to JsonPrimitive(year),
                    "saved" to JsonPrimitive(saved),
                ),
            )
        // Album pages often leave album and art off the songs; fill them from the album so rows look right.
        val tracks =
            page.songs.map { song ->
                val track = song.toTrack()
                trackJson(track.copy(album = track.album ?: albumRef, art = track.art ?: card.art))
            }
        val moreBy =
            page.artistAlbums.takeIf { it.isNotEmpty() }?.let { albums ->
                val artist = page.album.artists.firstOrNull()?.name
                Shelf(
                    key = "moreBy",
                    title = if (artist != null) "More by $artist" else "More",
                    layout = "rows",
                    items = albums.map { cardJson("album", it.toCard()) },
                ).toJson()
            }
        return jsonOf(
            "album" to album,
            "tracks" to JsonArray(tracks),
            "otherVersions" to JsonArray(page.otherVersions.map { it.toCard().toJson() }),
            "moreBy" to moreBy,
        )
    }

    // --- artist.get / artist.about -------------------------------------------------------------------------------

    fun artistHeader(page: ArtistPageResult): ArtistItem? = page.sections["header"]?.firstOrNull() as? ArtistItem

    /** Every section except the header, in order; the top-songs section is `rows`, the rest `cards`; keys are unique. */
    fun artistShelves(page: ArtistPageResult): List<Shelf> {
        val used = hashSetOf<String>()
        val topTracks = page.topTracksSection
        return page.sections
            .filterKeys { it != "header" }
            .mapNotNull { (title, items) ->
                val mapped = items.toItems()
                if (mapped.isEmpty()) return@mapNotNull null
                val base = page.sectionBrowseIds[title]?.first ?: slug(title)
                var key = base
                var n = 2
                while (!used.add(key)) key = "$base-${n++}"
                Shelf(key = key, title = title, layout = if (title == topTracks) "rows" else "cards", items = mapped)
            }
    }

    fun artistResult(
        page: ArtistPageResult,
        requestedId: String,
        subscribed: Boolean = false,
    ): JsonObject {
        val header = artistHeader(page)
        val card = header?.toCard() ?: ArtistCard(id = requestedId, name = "", source = sourceOf(requestedId).key)
        val artist =
            card.toJson().jsonObject.merged(
                jsonOf(
                    "banner" to header?.banner?.takeIf { it.isNotBlank() }?.let { Art(src = it).toJson() },
                    "subscribers" to header?.subscribers.json(),
                    "description" to header?.description.json(),
                    "subscribed" to JsonPrimitive(subscribed),
                    "monthly" to header?.monthlyListeners.json(),
                ),
            )
        return jsonOf(
            "artist" to artist,
            "shelves" to JsonArray(artistShelves(page).map { it.toJson() }),
            "sources" to JsonArray(listOf(JsonPrimitive("yt"))),
        )
    }

    /** Titles of the top songs, the catalogue hint for the Last.fm artist match. */
    fun catalogTitles(page: ArtistPageResult): List<String> =
        page.topTracksSection
            ?.let { page.sections[it] }
            .orEmpty()
            .filterIsInstance<SongItem>()
            .map { it.title }

    /** "1,234,567" to 1234567; null when there are no digits. */
    fun parseCount(text: String?): Long? = text?.filter(Char::isDigit)?.toLongOrNull()

    fun aboutResult(data: LastFmArtistData?): JsonObject {
        if (data == null) return JsonObject(emptyMap())
        val hasStats = data.listeners != null || data.scrobbles != null || data.tags.isNotEmpty()
        val lastfm =
            if (hasStats) {
                jsonOf(
                    "listeners" to JsonPrimitive(parseCount(data.listeners) ?: 0L),
                    "playcount" to JsonPrimitive(parseCount(data.scrobbles) ?: 0L),
                    "tags" to JsonArray(data.tags.map { JsonPrimitive(it) }),
                )
            } else {
                null
            }
        return jsonOf("bio" to data.summary?.takeIf { it.isNotBlank() }.json(), "lastfm" to lastfm)
    }

    // --- artist.tracks / playlist.get / podcast.* ----------------------------------------------------------------

    fun trackPage(
        songs: List<SongItem>,
        next: String?,
    ): JsonObject = jsonOf("items" to tracksJson(songs), "next" to cursorJson(next))

    /** `PlaylistCard & {description?, durationMs?, editable, revision}`; `revision` is the hash of the loaded track ids. */
    fun playlistHeader(
        id: String,
        header: PlaylistItem?,
        songs: List<SongItem>,
        complete: Boolean,
    ): JsonObject {
        val card = header?.toCard() ?: PlaylistCard(id = id, title = "", source = sourceOf(id).key, local = false)
        val durationMs = if (complete) songs.sumOf { it.duration ?: 0L } * 1000 else 0L
        return card.toJson().jsonObject.merged(
            jsonOf(
                "description" to header?.description.json(),
                "durationMs" to durationMs.takeIf { it > 0 }?.let { JsonPrimitive(it) },
                "editable" to JsonPrimitive(false),
                "revision" to JsonPrimitive(songs.map { it.id }.hashCode()),
            ),
        )
    }

    fun playlistResult(
        id: String,
        header: PlaylistItem?,
        allSongs: List<SongItem>,
        newSongs: List<SongItem>,
        hasMore: Boolean,
        next: String?,
    ): JsonObject =
        jsonOf(
            "playlist" to playlistHeader(id, header, allSongs, complete = !hasMore),
            "tracks" to tracksJson(newSongs),
            "next" to cursorJson(next),
        )

    /** Episodes as playable tracks (their `toSongItem` carries the podcast as the album). */
    fun episodeSongs(page: PodcastPage): List<SongItem> = page.episodes.map { it.toSongItem(page.podcast.title) }

    /** Podcast header in the playlist shape, so the Playlist page can show it (frontend-spec route table). */
    fun podcastResult(
        page: PodcastPage,
        next: String?,
    ): JsonObject {
        val podcast = page.podcast
        val card =
            PlaylistCard(
                id = podcast.id,
                title = podcast.title,
                subtitle = podcast.author,
                art = podcast.thumbnail?.takeIf { it.isNotBlank() }?.let { Art(src = it) },
                source = sourceOf(podcast.id).key,
                owner = podcast.author,
                local = false,
                count = podcast.episodeCount?.filter(Char::isDigit)?.toIntOrNull(),
            )
        val songs = episodeSongs(page)
        val header =
            card.toJson().jsonObject.merged(
                jsonOf(
                    "description" to page.description.json(),
                    "editable" to JsonPrimitive(false),
                    "revision" to JsonPrimitive(songs.map { it.id }.hashCode()),
                ),
            )
        return jsonOf("playlist" to header, "tracks" to tracksJson(songs), "next" to cursorJson(next))
    }

    // --- related.get ---------------------------------------------------------------------------------------------

    fun relatedResult(songs: List<SongItem>): JsonObject {
        if (songs.isEmpty()) return jsonOf("shelves" to JsonArray(emptyList()))
        val shelf = Shelf(key = "related", title = "Related", layout = "rows", items = songs.toItems())
        return jsonOf("shelves" to JsonArray(listOf(shelf.toJson())))
    }
}
