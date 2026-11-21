package com.metrolist.shared.source.soundcloud

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.mm_chart_sc_trending
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.source.MusicSource
import com.metrolist.shared.source.SourceCapability
import com.metrolist.shared.source.SourceChartSection
import com.metrolist.shared.source.SourceContentException
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourcePage
import com.metrolist.shared.source.SourcePlaylist
import com.metrolist.shared.source.SourceQuality
import com.metrolist.shared.source.sourceNetworkFailure
import com.metrolist.shared.source.sourceUnavailable
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.net.URLEncoder
import kotlin.time.Instant

/** SoundCloud as a [MusicSource]. Pages are zero-based; ids are `sc:<numeric id>`, users `sc:user:<numeric id>`. */
class SoundCloudSource(
    private val client: SoundCloudClient,
) : MusicSource {
    override val id = SourceId.SC
    override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.CHARTS, SourceCapability.DOWNLOAD)

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        page: Int,
    ): SourcePage<YTItem> {
        val scFilter =
            when (filter) {
                SearchFilter.ALBUM, SearchFilter.FEATURED_PLAYLIST, SearchFilter.COMMUNITY_PLAYLIST -> SoundCloudSearchFilter.PLAYLISTS
                SearchFilter.ARTIST, SearchFilter.PROFILE -> SoundCloudSearchFilter.USERS
                else -> SoundCloudSearchFilter.TRACKS
            }
        // api-v2 pages by offset; the client accepts any api-v2 URL as a page token.
        val pageToken =
            if (page > 0) {
                "${SoundCloudClient.API_URL}search/${scFilter.pathSegment}?q=${URLEncoder.encode(query, "UTF-8")}" +
                    "&limit=$PAGE_SIZE&offset=${page * PAGE_SIZE}"
            } else {
                null
            }
        val result = client.search(query, scFilter, pageToken)
        return SourcePage(
            items = result.items.map(SoundCloudItems::item),
            continuation = (page + 1).toString().takeIf { result.nextPageToken != null },
        )
    }

    override suspend fun track(id: String): SongItem = SoundCloudItems.song(client.track(id))

    override suspend fun stream(
        id: String,
        quality: SourceQuality,
    ): PlaybackResource {
        val stream =
            try {
                client.stream(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SoundCloudException.PreviewOnly) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.PREVIEW_ONLY, error)
            } catch (error: SoundCloudException.Blocked) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.GEO_BLOCKED, error)
            } catch (error: SoundCloudException.Unavailable) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.NOT_FOUND, error)
            } catch (error: SoundCloudException.NoPlayableStream) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.NOT_FOUND, error)
            } catch (error: IOException) {
                throw sourceNetworkFailure(this.id, error)
            }
        return SoundCloudItems.playbackResource(id, stream)
    }

    override suspend fun album(id: String): AlbumPage? {
        val playlist = client.playlist(id)
        return AlbumPage(album = SoundCloudItems.album(playlist.summary), songs = playlist.tracks.map(SoundCloudItems::song))
    }

    override suspend fun playlist(id: String): SourcePlaylist? {
        val playlist = client.playlist(id)
        return SourcePlaylist(SoundCloudItems.playlist(playlist.summary), playlist.tracks.map(SoundCloudItems::song))
    }

    /** The global trending chart (SoundCloud has no regional charts); later pages through [chartPage]. */
    override suspend fun charts(): List<SourceChartSection> {
        val page = client.charts()
        val tracks = page.items.map(SoundCloudItems::song)
        return listOf(
            SourceChartSection(getString(Res.string.mm_chart_sc_trending), tracks, id = TRENDING_CHART, continuation = page.nextPageToken),
        )
    }

    /** [continuation] is the api-v2 next_href of the previous page. */
    override suspend fun chartPage(
        chartId: String,
        continuation: String,
    ): SourcePage<YTItem> {
        if (chartId != TRENDING_CHART) return SourcePage(emptyList())
        val page = client.charts(continuation)
        return SourcePage(page.items.map(SoundCloudItems::song), continuation = page.nextPageToken)
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val TRENDING_CHART = "trending"
    }
}

internal object SoundCloudItems {
    const val USER_ID_PREFIX = "sc:user:"

    fun item(item: SoundCloudSearchItem): YTItem =
        when (item) {
            is SoundCloudSearchItem.Track -> song(item.track)
            is SoundCloudSearchItem.Playlist -> if (item.playlist.isAlbum) album(item.playlist) else playlist(item.playlist)
            is SoundCloudSearchItem.User -> artist(item.user)
        }

    fun song(track: SoundCloudTrack): SongItem =
        SongItem(
            id = track.id,
            title = track.title,
            thumbnail = track.artworkUrl,
            artists = listOf(ArtistTiny(id = null, name = track.artist)),
            album = track.album?.takeIf(String::isNotBlank)?.let { AlbumTiny(id = null, name = it) },
            duration = (track.durationMs / 1000).takeIf { it > 0 },
            isUnavailable = !track.isPlayable,
        )

    fun album(summary: SoundCloudPlaylistSummary): AlbumItem =
        AlbumItem(
            id = SOUNDCLOUD_ID_PREFIX + summary.numericId,
            title = summary.title,
            thumbnail = summary.artworkUrl,
            artists = listOf(ArtistTiny(id = null, name = summary.artist)),
            playlistId = SOUNDCLOUD_ID_PREFIX + summary.numericId,
            songCount = summary.trackCount,
        )

    fun playlist(summary: SoundCloudPlaylistSummary): PlaylistItem =
        PlaylistItem(
            id = SOUNDCLOUD_ID_PREFIX + summary.numericId,
            title = summary.title,
            thumbnail = summary.artworkUrl,
            author = summary.artist,
            songCount = summary.trackCount.toString(),
        )

    fun artist(user: SoundCloudUser): ArtistItem =
        ArtistItem(
            id = USER_ID_PREFIX + user.numericId,
            title = user.name,
            thumbnail = user.avatarUrl,
        )

    fun playbackResource(
        id: String,
        stream: SoundCloudStream,
    ): PlaybackResource =
        PlaybackResource(
            videoId = id,
            url = stream.url,
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = Instant.fromEpochMilliseconds(stream.expiresAtMs),
            itag = -1,
            mimeType = stream.mimeType,
            codecs =
                when (stream.codec) {
                    "aac" -> "mp4a.40.2"
                    else -> stream.codec
                },
            bitrate = stream.bitrateKbps * 1000,
            clientName = SourceId.SC.key,
            profileId = "${SourceId.SC.key}:${stream.preset}",
            requireBoundedRange = false,
        )
}
