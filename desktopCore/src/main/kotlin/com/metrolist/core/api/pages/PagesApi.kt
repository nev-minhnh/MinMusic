package com.metrolist.core.api.pages

import com.metrolist.core.api.browse.BrowseCursor
import com.metrolist.core.api.browse.sourceRegistry
import com.metrolist.core.api.browse.withSourceErrors
import com.metrolist.core.api.library.LibraryFlags
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.handler
import com.metrolist.core.server.notFound
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.sourceOf
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.LastFmArtistCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonElement

/** Where a `playlist.get` continuation resumes: the page loaded so far and how many of its songs were already sent. */
internal class PlaylistCursor(
    val page: PlaylistPage,
    val sent: Int,
)

/** Loads an album from YouTube, or from the source named by the id prefix (`sc:`, `zing:`, `nct:`); null when unknown. */
internal suspend fun loadAlbum(id: String): AlbumPage? =
    when (val source = sourceOf(id)) {
        SourceId.YT -> {
            // getAlbum(albumId, loadAllPages = true, applyContentFilters = true): AlbumPage?
            GlobalYouTubeRepository.instance.getAlbum(id)
        }

        SourceId.LOCAL -> {
            null
        }

        else -> {
            // MusicSource.album(id): AlbumPage?
            withSourceErrors { sourceRegistry().call(source) { it.album(id) } }
        }
    }

/** api.md 3.5. */
object PagesApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "album.get" to handler { params, _ -> albumGet(params.requireStr("id")) },
            "artist.get" to handler { params, _ -> artistGet(params.requireStr("id")) },
            "artist.about" to handler { params, _ -> artistAbout(params.requireStr("id"), params.str("name")) },
            "artist.tracks" to handler { params, _ -> artistTracks(params.requireStr("id"), params.str("cursor")) },
            "playlist.get" to handler { params, _ -> playlistGet(params.requireStr("id"), params.str("cursor")) },
            "podcast.get" to handler { params, _ -> podcastGet(params.requireStr("id")) },
            "podcast.episodes" to handler { params, _ -> podcastEpisodes(params.requireStr("id"), params.str("cursor")) },
            "related.get" to handler { params, _ -> relatedGet(params.requireStr("trackId")) },
        )

    private val repo get() = GlobalYouTubeRepository.instance

    private suspend fun albumGet(id: String): JsonElement {
        backendReady()
        val page = loadAlbum(id) ?: notFound("album $id not found")
        return PageMappers.albumResult(page, LibraryFlags.albumSaved(id))
    }

    private suspend fun artistGet(id: String): JsonElement {
        backendReady()
        // getArtist(channelId: String): ArtistPageResult
        val page = repo.getArtist(id)
        val subscribed = LibraryFlags.artistSubscribed(id, PageMappers.artistHeader(page)?.isSubscribed ?: false)
        return PageMappers.artistResult(page, id, subscribed)
    }

    private suspend fun artistAbout(
        id: String,
        name: String?,
    ): JsonElement {
        backendReady()
        val page = repo.getArtist(id)
        val artistName = name ?: PageMappers.artistHeader(page)?.title.orEmpty()
        val cache = LastFmArtistCache.shared
        try {
            // refresh(artistId, artistName, catalogTitles): suspend; get(artistId): LastFmArtistData?
            cache.refresh(id, artistName, PageMappers.catalogTitles(page))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Last.fm is optional: answer with whatever the cache already holds.
        }
        return PageMappers.aboutResult(cache.get(id))
    }

    private suspend fun artistTracks(
        id: String,
        cursor: String?,
    ): JsonElement {
        backendReady()
        if (cursor != null) {
            val (token, _) = BrowseCursor.unwrap(cursor)
            val (sections, next) = repo.getHomeSectionsContinuation(token, isPersonal = true, loadAllPages = false)
            return PageMappers.trackPage(sections.flatMap { it.items }.filterIsInstance<SongItem>(), BrowseCursor.wrap(next, true))
        }
        val page: ArtistPageResult = repo.getArtist(id)
        val (browseId, browseParams) = page.topTracksSection?.let { page.sectionBrowseIds[it] } ?: (null to null)
        if (browseId == null) {
            // No "top songs" browse endpoint on this page: fall back to the loaded songs.
            val songs = repo.getArtistSongs(id, page)
            return PageMappers.trackPage(songs, null)
        }
        val data = repo.getBrowsePageData(browseId, browseParams, loadAllPages = false)
        val songs = data.sections.flatMap { it.items }.filterIsInstance<SongItem>()
        return PageMappers.trackPage(songs, BrowseCursor.wrap(data.continuation, true))
    }

    private suspend fun playlistGet(
        id: String,
        cursor: String?,
    ): JsonElement {
        backendReady()
        val sentBefore: Int
        val page: PlaylistPage
        if (cursor == null) {
            // getPlaylistPages(playlistId, applyContentFilters = true): Flow<PlaylistPage>; the first emission is page one.
            page = repo.getPlaylistPages(id, false).first()
            sentBefore = 0
        } else {
            val previous = PageCursors.get<PlaylistCursor>(cursor) ?: badRequest("stale cursor")
            // continuePlaylistPage(page, applyContentFilters): PlaylistPage; its songs include the earlier pages.
            page = repo.continuePlaylistPage(previous.page, false)
            sentBefore = previous.sent
        }
        val all = page.songs
        val next = if (page.hasMore) PageCursors.put(PlaylistCursor(page, all.size)) else null
        val header = page.sections["header"]?.firstOrNull() as? PlaylistItem
        return PageMappers.playlistResult(id, header, all, all.drop(sentBefore), page.hasMore, next)
    }

    private suspend fun podcastGet(id: String): JsonElement {
        backendReady()
        val page = repo.getPodcastPages(id).first()
        val next = if (page.continuation != null) PageCursors.put(page) else null
        return PageMappers.podcastResult(page, next)
    }

    private suspend fun podcastEpisodes(
        id: String,
        cursor: String?,
    ): JsonElement {
        backendReady()
        if (cursor == null) badRequest("missing 'cursor'")
        val previous = PageCursors.get<PodcastPage>(cursor) ?: badRequest("stale cursor")
        // getPodcastPages(id, initialPage = page) emits the page itself first, then each continuation page (new episodes only).
        val page = repo.getPodcastPages(id, initialPage = previous).drop(1).firstOrNull()
        if (page == null) return PageMappers.trackPage(emptyList(), null)
        val next = if (page.continuation != null) PageCursors.put(page) else null
        return PageMappers.trackPage(PageMappers.episodeSongs(page), next)
    }

    private suspend fun relatedGet(trackId: String): JsonElement {
        backendReady()
        if (sourceOf(trackId) != SourceId.YT) return PageMappers.relatedResult(emptyList())
        // startRadio(videoId): List<SongItem>; the first entry is the seed itself.
        val songs = repo.startRadio(trackId).drop(1).take(RELATED_LIMIT)
        return PageMappers.relatedResult(songs)
    }

    private const val RELATED_LIMIT = 25
}
