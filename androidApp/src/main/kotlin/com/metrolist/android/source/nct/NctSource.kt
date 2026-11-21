package com.metrolist.android.source.nct

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
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
import com.metrolist.shared.source.nct.NCT_ID_PREFIX
import com.metrolist.shared.source.nct.NctArtist
import com.metrolist.shared.source.nct.NctClient
import com.metrolist.shared.source.nct.NctEntitlement
import com.metrolist.shared.source.nct.NctException
import com.metrolist.shared.source.nct.NctPlaylist
import com.metrolist.shared.source.nct.NctSong
import com.metrolist.shared.source.nct.NctStream
import com.metrolist.shared.source.sourceNetworkFailure
import com.metrolist.shared.source.sourceUnavailable
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/**
 * NhacCuaTui as a [MusicSource]. Only song search exists; pages are zero-based. Free sessions get streams that are
 * not VIP-only, VIP sessions the best stream overall.
 */
class NctSource(
    private val client: NctClient,
) : MusicSource {
    override val id = SourceId.NCT
    override val capabilities =
        setOf(SourceCapability.SEARCH, SourceCapability.CHARTS, SourceCapability.LYRICS, SourceCapability.LOGIN, SourceCapability.DOWNLOAD)

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        page: Int,
    ): SourcePage<YTItem> {
        if (filter != SearchFilter.SONG && filter != SearchFilter.VIDEO) return SourcePage(emptyList())
        val result = client.search(query, page.coerceAtLeast(0) + 1)
        return SourcePage(result.songs.map(NctItems::song), continuation = (page + 1).toString().takeIf { result.hasMore })
    }

    override suspend fun track(id: String): SongItem = NctItems.song(client.song(id))

    override suspend fun stream(
        id: String,
        quality: SourceQuality,
    ): PlaybackResource {
        val stream =
            try {
                client.stream(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NctException.VipRequired) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.VIP_REQUIRED, error)
            } catch (error: NctException.NotFound) {
                throw sourceUnavailable(this.id, SourceContentException.Reason.NOT_FOUND, error)
            } catch (error: NctException.Network) {
                throw sourceNetworkFailure(this.id, error)
            }
        return NctItems.playbackResource(id, stream)
    }

    override suspend fun album(id: String): AlbumPage? {
        val playlist = client.playlist(id)
        return AlbumPage(album = NctItems.album(playlist), songs = playlist.songs.map(NctItems::song), description = playlist.description)
    }

    override suspend fun playlist(id: String): SourcePlaylist? {
        val playlist = client.playlist(id)
        return SourcePlaylist(NctItems.playlist(playlist), playlist.songs.map(NctItems::song))
    }

    /**
     * Every NhacCuaTui chart, titled as NhacCuaTui names them. Only the first [EAGER_CHARTS] come with their songs (Home
     * shows those); the rest load on demand through [chartPage], so opening Home does not cost a request per chart.
     */
    override suspend fun charts(): List<SourceChartSection> =
        client.charts().mapIndexed { index, chart ->
            if (index < EAGER_CHARTS) {
                SourceChartSection(chart.title, client.chart(chart.id).songs.map(NctItems::song), id = chart.id)
            } else {
                SourceChartSection(chart.title, emptyList(), id = chart.id, continuation = FULL_CHART)
            }
        }

    /** The whole chart [chartId] (NhacCuaTui serves each chart in one response). */
    override suspend fun chartPage(
        chartId: String,
        continuation: String,
    ): SourcePage<YTItem> = SourcePage(client.chart(chartId).songs.map(NctItems::song))

    /** Synced LRC when NhacCuaTui has it, otherwise plain text. */
    override suspend fun lyrics(id: String): String? = client.lyrics(id)?.let { it.synced ?: it.plain }

    override suspend fun account(): SourceAccount? {
        val account = client.account()
        if (!account.loggedIn) return null
        return SourceAccount(
            source = id,
            displayName = account.displayName,
            entitlement = if (account.entitlement == NctEntitlement.VIP) SourceEntitlement.VIP else SourceEntitlement.FREE,
        )
    }

    private companion object {
        const val EAGER_CHARTS = 2
        const val FULL_CHART = "full"
    }
}

internal object NctItems {
    fun song(song: NctSong): SongItem =
        SongItem(
            id = song.id,
            title = song.title,
            thumbnail = song.largeArtworkUrl ?: song.artworkUrl,
            artists = artists(song.artists),
            album = song.album?.let { AlbumTiny(id = NCT_ID_PREFIX + it.key, name = it.title) },
            duration = (song.durationMs / 1000).takeIf { it > 0 },
        )

    fun album(playlist: NctPlaylist): AlbumItem =
        AlbumItem(
            id = playlist.id,
            title = playlist.title,
            thumbnail = playlist.artworkUrl,
            artists = listOfNotNull(playlist.author?.let { ArtistTiny(id = null, name = it) }),
            playlistId = playlist.id,
            songCount = playlist.songCount,
        )

    fun playlist(playlist: NctPlaylist): PlaylistItem =
        PlaylistItem(
            id = playlist.id,
            title = playlist.title,
            thumbnail = playlist.artworkUrl,
            author = playlist.author,
            songCount = playlist.songCount.toString(),
            description = playlist.description,
        )

    fun playbackResource(
        id: String,
        stream: NctStream,
    ): PlaybackResource =
        PlaybackResource(
            videoId = id,
            url = stream.url,
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = stream.expiresAtMs?.let { Instant.fromEpochMilliseconds(it) },
            itag = -1,
            mimeType = stream.mimeType,
            codecs = stream.codec,
            bitrate = stream.bitrate,
            clientName = SourceId.NCT.key,
            profileId = "${SourceId.NCT.key}:${stream.quality}",
            requireBoundedRange = false,
        )

    private fun artists(artists: List<NctArtist>): List<ArtistTiny> =
        artists.map { ArtistTiny(id = it.key.takeIf(String::isNotEmpty)?.let { key -> NCT_ID_PREFIX + "artist:" + key }, name = it.name) }
}
