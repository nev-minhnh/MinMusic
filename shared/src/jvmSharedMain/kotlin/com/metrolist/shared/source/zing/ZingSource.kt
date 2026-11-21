package com.metrolist.shared.source.zing

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.charts
import com.metrolist.shared.generated.resources.mm_chart_zing_week_kr
import com.metrolist.shared.generated.resources.mm_chart_zing_week_us
import com.metrolist.shared.generated.resources.mm_chart_zing_week_vn
import com.metrolist.shared.generated.resources.new_releases
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
import com.metrolist.shared.source.SourceAccount
import com.metrolist.shared.source.SourceCapability
import com.metrolist.shared.source.SourceChartSection
import com.metrolist.shared.source.SourceContentException
import com.metrolist.shared.source.SourceEntitlement
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourcePage
import com.metrolist.shared.source.SourcePlaylist
import com.metrolist.shared.source.SourceQuality
import com.metrolist.shared.source.sourceNetworkFailure
import com.metrolist.shared.source.sourceUnavailable
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.getString
import kotlin.time.Instant

/**
 * Zing MP3 as a [MusicSource]. Pages are zero-based; artists are `zing:artist:<alias>`. Streams come from the best tier
 * the user's session may play (lossless for [SourceQuality.HIGH] and up); "VIP" placeholder tiers are never requested.
 */
class ZingSource(
    private val client: ZingClient,
) : MusicSource {
    override val id = SourceId.ZING
    override val capabilities =
        setOf(SourceCapability.SEARCH, SourceCapability.CHARTS, SourceCapability.LYRICS, SourceCapability.LOGIN, SourceCapability.DOWNLOAD)

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        page: Int,
    ): SourcePage<YTItem> {
        val type =
            when (filter) {
                SearchFilter.ALBUM, SearchFilter.FEATURED_PLAYLIST, SearchFilter.COMMUNITY_PLAYLIST -> ZingSearchType.PLAYLIST
                SearchFilter.ARTIST, SearchFilter.PROFILE -> ZingSearchType.ARTIST
                else -> ZingSearchType.SONG
            }
        val result = client.search(query, type, page.coerceAtLeast(0) + 1)
        val items = result.songs.map(ZingItems::song) + result.playlists.map(ZingItems::playlistRef) + result.artists.map(ZingItems::artist)
        return SourcePage(items, continuation = (page + 1).toString().takeIf { result.hasMore })
    }

    override suspend fun track(id: String): SongItem = ZingItems.song(client.song(id))

    override suspend fun stream(
        id: String,
        quality: SourceQuality,
    ): PlaybackResource {
        val stream =
            try {
                client.stream(id, preferLossless = quality >= SourceQuality.HIGH)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ZingException.GeoBlocked) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.GEO_BLOCKED, error)
            } catch (error: ZingException.AuthRequired) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.VIP_REQUIRED, error)
            } catch (error: ZingException.NotFound) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.NOT_FOUND, error)
            } catch (error: ZingException.Network) {
                throw sourceNetworkFailure(this.id, error)
            }
        return ZingItems.playbackResource(id, stream)
    }

    override suspend fun album(id: String): AlbumPage? {
        val playlist = client.album(id)
        return AlbumPage(album = ZingItems.album(playlist), songs = playlist.songs.map(ZingItems::song), description = playlist.description)
    }

    override suspend fun playlist(id: String): SourcePlaylist? {
        val playlist = client.playlist(id)
        return SourcePlaylist(ZingItems.playlist(playlist), playlist.songs.map(ZingItems::song))
    }

    /**
     * The realtime #zingchart (complete, as chart-home serves it), new releases and the weekly charts. chart-home only
     * previews the last two, so their continuation loads the full list from the dedicated endpoints.
     */
    override suspend fun charts(): List<SourceChartSection> {
        val charts = client.charts()
        val weekly =
            charts.weekly.mapNotNull { week ->
                val playlistId = week.playlistId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (week.songs.isEmpty()) return@mapNotNull null
                val title =
                    when (week.region.lowercase()) {
                        "vn" -> getString(Res.string.mm_chart_zing_week_vn)
                        "us" -> getString(Res.string.mm_chart_zing_week_us)
                        "korea", "kr" -> getString(Res.string.mm_chart_zing_week_kr)
                        else -> week.region
                    }
                SourceChartSection(title, week.songs.map(ZingItems::song), id = WEEK_CHART_PREFIX + playlistId, continuation = FULL_CHART)
            }
        return listOfNotNull(
            charts.realtime.takeIf { it.isNotEmpty() }?.let {
                SourceChartSection(getString(Res.string.charts), it.map(ZingItems::song), id = REALTIME_CHART)
            },
            charts.newReleases.takeIf { it.isNotEmpty() }?.let {
                SourceChartSection(
                    getString(Res.string.new_releases),
                    it.map(ZingItems::song),
                    id = NEW_RELEASE_CHART,
                    continuation = FULL_CHART,
                )
            },
        ) + weekly
    }

    override suspend fun chartPage(
        chartId: String,
        continuation: String,
    ): SourcePage<YTItem> {
        val songs =
            when {
                chartId == NEW_RELEASE_CHART -> client.newReleases()
                chartId.startsWith(WEEK_CHART_PREFIX) -> client.weekChart(chartId.removePrefix(WEEK_CHART_PREFIX)).songs
                else -> emptyList()
            }
        return SourcePage(songs.map(ZingItems::song))
    }

    override suspend fun lyrics(id: String): String? = client.lyrics(id)

    override suspend fun account(): SourceAccount? {
        val account = client.account()
        if (!account.loggedIn) return null
        return SourceAccount(
            source = id,
            displayName = account.displayName,
            entitlement = if (account.entitlement == ZingEntitlement.VIP) SourceEntitlement.VIP else SourceEntitlement.FREE,
        )
    }

    private companion object {
        const val REALTIME_CHART = "realtime"
        const val NEW_RELEASE_CHART = "new-release"
        const val WEEK_CHART_PREFIX = "week:"
        const val FULL_CHART = "full"
    }
}

internal object ZingItems {
    const val ARTIST_ID_PREFIX = "${ZING_ID_PREFIX}artist:"

    fun song(song: ZingSong): SongItem =
        SongItem(
            id = song.id,
            title = song.title,
            thumbnail = song.artworkUrl,
            artists = artists(song.artists, song.artistsText),
            album = song.album?.let { AlbumTiny(id = it.id, name = it.title) },
            duration = song.durationSeconds?.toLong()?.takeIf { it > 0 },
        )

    fun playlistRef(ref: ZingPlaylistRef): YTItem =
        if (ref.isAlbum) {
            AlbumItem(
                id = ref.id,
                title = ref.title,
                thumbnail = ref.artworkUrl,
                artists = artists(emptyList(), ref.artistsText),
                playlistId = ref.id,
            )
        } else {
            PlaylistItem(id = ref.id, title = ref.title, thumbnail = ref.artworkUrl, author = ref.artistsText)
        }

    fun album(playlist: ZingPlaylist): AlbumItem =
        AlbumItem(
            id = playlist.id,
            title = playlist.title,
            thumbnail = playlist.artworkUrl,
            artists = artists(emptyList(), playlist.artistsText),
            playlistId = playlist.id,
            songCount = playlist.total ?: playlist.songs.size,
        )

    fun playlist(playlist: ZingPlaylist): PlaylistItem =
        PlaylistItem(
            id = playlist.id,
            title = playlist.title,
            thumbnail = playlist.artworkUrl,
            author = playlist.artistsText,
            songCount = (playlist.total ?: playlist.songs.size).toString(),
            description = playlist.description,
        )

    fun artist(artist: ZingArtistSummary): ArtistItem =
        ArtistItem(
            id = ARTIST_ID_PREFIX + artist.alias,
            title = artist.name,
            thumbnail = artist.artworkUrl,
        )

    fun playbackResource(
        id: String,
        stream: ZingStream,
    ): PlaybackResource =
        PlaybackResource(
            videoId = id,
            url = stream.url,
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = stream.expiresAtMs?.let { Instant.fromEpochMilliseconds(it) },
            itag = -1,
            mimeType = stream.mimeType,
            codecs = if (stream.quality == ZingQuality.LOSSLESS) "flac" else "mp3",
            bitrate = stream.bitrateKbps?.times(1000),
            clientName = SourceId.ZING.key,
            profileId = "${SourceId.ZING.key}:${stream.quality.key}",
            requireBoundedRange = false,
        )

    private fun artists(
        refs: List<ZingArtistRef>,
        text: String?,
    ): List<ArtistTiny> =
        refs.map { ArtistTiny(id = it.alias?.let { alias -> ARTIST_ID_PREFIX + alias }, name = it.name) }.ifEmpty {
            text
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.map { ArtistTiny(id = null, name = it) }
                .orEmpty()
        }
}
