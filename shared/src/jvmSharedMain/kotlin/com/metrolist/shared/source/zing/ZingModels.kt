package com.metrolist.shared.source.zing

const val ZING_ID_PREFIX = "zing:"

data class ZingArtistRef(
    val id: String?,
    val name: String,
    val alias: String?,
)

data class ZingAlbumRef(
    val id: String,
    val title: String,
    val artworkUrl: String?,
)

data class ZingSong(
    /** Namespaced id, "zing:<encodeId>". */
    val id: String,
    val zingId: String,
    val title: String,
    val artists: List<ZingArtistRef>,
    val artistsText: String?,
    val album: ZingAlbumRef?,
    val durationSeconds: Int?,
    val artworkUrl: String?,
    val isWorldWide: Boolean?,
    val streamingStatus: Int?,
    val streamPrivileges: List<Int>,
    val downloadPrivileges: List<Int>,
    val hasLyric: Boolean,
)

enum class ZingQuality(
    val key: String,
    val bitrateKbps: Int?,
    val mimeType: String,
) {
    LOSSLESS("lossless", null, "audio/flac"),
    KBPS_320("320", 320, "audio/mpeg"),
    KBPS_128("128", 128, "audio/mpeg"),
}

data class ZingStream(
    val url: String,
    val quality: ZingQuality,
    val mimeType: String,
    val bitrateKbps: Int?,
    /** From the `exp` field of the signed CDN URL, when present. */
    val expiresAtMs: Long?,
)

data class ZingPlaylistRef(
    val id: String,
    val zingId: String,
    val title: String,
    val artworkUrl: String?,
    val artistsText: String?,
    val isAlbum: Boolean,
)

data class ZingPlaylist(
    val id: String,
    val zingId: String,
    val title: String,
    val artworkUrl: String?,
    val artistsText: String?,
    val description: String?,
    val isAlbum: Boolean,
    val songs: List<ZingSong>,
    val total: Int?,
)

data class ZingArtistSummary(
    val zingId: String?,
    val alias: String,
    val name: String,
    val artworkUrl: String?,
    val followers: Long?,
)

data class ZingArtist(
    val summary: ZingArtistSummary,
    val biography: String?,
    val topSongs: List<ZingSong>,
    val playlists: List<ZingPlaylistRef>,
)

enum class ZingSearchType(
    val param: String,
) {
    SONG("song"),
    PLAYLIST("playlist"),
    ARTIST("artist"),
}

data class ZingSearchResult(
    val songs: List<ZingSong>,
    val playlists: List<ZingPlaylistRef>,
    val artists: List<ZingArtistSummary>,
    val total: Int?,
    val hasMore: Boolean,
)

data class ZingWeekChart(
    val region: String,
    val playlistId: String?,
    val week: Int?,
    val year: Int?,
    val songs: List<ZingSong>,
)

data class ZingCharts(
    val realtime: List<ZingSong>,
    val newReleases: List<ZingSong>,
    val weekly: List<ZingWeekChart>,
)

data class ZingSection(
    val title: String?,
    val playlists: List<ZingPlaylistRef>,
)

enum class ZingEntitlement {
    FREE,
    VIP,
    UNKNOWN,
}

data class ZingAccount(
    val loggedIn: Boolean,
    val userId: String?,
    val displayName: String?,
    val entitlement: ZingEntitlement,
)
