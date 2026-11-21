package com.metrolist.shared.source

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.youtube.playback.PlaybackResource

enum class SourceCapability {
    SEARCH,
    CHARTS,
    LYRICS,
    RADIO,
    LOGIN,
    DOWNLOAD,
}

enum class SourceQuality {
    LOW,
    STANDARD,
    HIGH,
    LOSSLESS,
}

enum class SourceEntitlement {
    FREE,
    VIP,
}

data class SourcePage<T>(
    val items: List<T>,
    val continuation: String? = null,
)

data class SourcePlaylist(
    val playlist: PlaylistItem,
    val songs: List<SongItem>,
)

data class SourceChartSection(
    val title: String,
    val items: List<YTItem>,
)

/** Login state of a source account; credentials never leave the source's encrypted store. */
data class SourceAccount(
    val source: SourceId,
    val displayName: String?,
    val entitlement: SourceEntitlement,
)

/** A catalogue other than YouTube. Ids passed in and returned carry the source's [SourceId.prefix]. */
interface MusicSource {
    val id: SourceId
    val capabilities: Set<SourceCapability>

    suspend fun search(
        query: String,
        filter: SearchFilter,
        page: Int,
    ): SourcePage<YTItem>

    suspend fun track(id: String): SongItem

    suspend fun stream(
        id: String,
        quality: SourceQuality,
    ): PlaybackResource

    suspend fun album(id: String): AlbumPage?

    suspend fun playlist(id: String): SourcePlaylist?

    suspend fun charts(): List<SourceChartSection>

    suspend fun lyrics(id: String): String? = null

    suspend fun account(): SourceAccount? = null
}

internal fun AudioQuality.toSourceQuality(): SourceQuality =
    when (this) {
        AudioQuality.LOW -> SourceQuality.LOW
        else -> SourceQuality.HIGH
    }
