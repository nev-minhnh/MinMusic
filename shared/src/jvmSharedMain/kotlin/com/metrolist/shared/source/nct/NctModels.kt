package com.metrolist.shared.source.nct

/** Song, playlist and chart ids use this prefix so they never collide with YouTube video ids. */
const val NCT_ID_PREFIX = "nct:"

data class NctArtist(
    val key: String,
    val name: String,
    val thumbnailUrl: String?,
)

data class NctAlbum(
    val key: String,
    val title: String,
    val artworkUrl: String?,
)

data class NctSong(
    val id: String,
    val key: String,
    val title: String,
    val artists: List<NctArtist>,
    val album: NctAlbum?,
    val durationMs: Long,
    val artworkUrl: String?,
    val largeArtworkUrl: String?,
    val streams: List<NctStream>,
)

data class NctStream(
    val quality: String,
    val url: String,
    val onlyVip: Boolean,
    val available: Boolean,
    val mimeType: String?,
    val codec: String?,
    val bitrate: Int?,
    val expiresAtMs: Long?,
)

data class NctSearchPage(
    val songs: List<NctSong>,
    val page: Int,
    val total: Int,
    val hasMore: Boolean,
)

data class NctPlaylist(
    val id: String,
    val key: String,
    val title: String,
    val description: String?,
    val artworkUrl: String?,
    val author: String?,
    val isAlbum: Boolean,
    val songCount: Int,
    val songs: List<NctSong>,
    val hasMore: Boolean,
)

data class NctChart(
    val id: String,
    val key: String,
    val title: String,
    val subtitle: String?,
    val category: String?,
    val artworkUrl: String?,
)

data class NctLyrics(
    val synced: String?,
    val plain: String?,
    val aiGenerated: Boolean,
)

enum class NctEntitlement { FREE, VIP }

data class NctAccount(
    val loggedIn: Boolean,
    val entitlement: NctEntitlement,
    val userId: String?,
    val displayName: String?,
) {
    companion object {
        val LoggedOut = NctAccount(loggedIn = false, entitlement = NctEntitlement.FREE, userId = null, displayName = null)
    }
}

class NctToken(
    val value: String,
    val expiresAtMs: Long?,
    val account: NctAccount,
) {
    override fun toString(): String = "NctToken(expiresAtMs=$expiresAtMs, account=$account)"
}

/** Persists the NCT session token. Implementations must store it encrypted and exclude it from backups. */
interface NctTokenStore {
    suspend fun load(): String?

    suspend fun save(token: String?)
}
