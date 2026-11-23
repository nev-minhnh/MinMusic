package com.metrolist.core.api.importlists

import com.metrolist.core.server.ApiException
import com.metrolist.core.shell.WebLoginResult
import com.metrolist.shared.amazonmusicimport.AmazonMusicImportManager
import com.metrolist.shared.deezerimport.DeezerImportManager
import com.metrolist.shared.network.sharedHttpEngine
import com.metrolist.shared.playlistimport.AppleMusicPlaylistImporter
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager.CsvTrack
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import com.metrolist.shared.spotifyimport.spotifySessionCookie
import com.metrolist.shared.tidalimport.TidalImportManager
import meow.nyx.spotlib.LogLevel
import meow.nyx.spotlib.PlaylistMeta
import meow.nyx.spotlib.SpotifyClient
import meow.nyx.spotlib.SpotifyConfig

/** A playlist ready to resolve: [title] names the new album, [coverUrl] is its cover when the provider has one. */
class LoadedImport(
    val title: String,
    val tracks: List<CsvTrack>,
    val coverUrl: String?,
)

/**
 * One import provider behind the shared managers (the v1 Compose flows are not used). Cookies and tokens go straight into the
 * manager and are never logged or stored here; Spotify's manager persists its own session as in v1.
 */
interface ImportProvider {
    val id: ImportProviderId

    /** The manager's `isAuthenticated` (Spotify: also a stored session). */
    val signedIn: Boolean

    /** Reads the credentials out of the finished login and hands them to the manager. */
    suspend fun authenticate(
        login: WebLoginResult,
        amazonHost: String? = null,
    )

    suspend fun listPlaylists(): List<ImportRow>

    /** The row of a playlist seen by the last [listPlaylists] (null when not listed yet). */
    fun known(playlistId: String): ImportRow?

    suspend fun load(playlistId: String): LoadedImport
}

private fun noCredentials(provider: ImportProviderId): Nothing = throw ApiException("not_signed_in", "${provider.wire} login did not return a session")

private const val LIKED_SONGS_NAME = "Liked Songs"

/** Spotify: `sp_dc` cookie, [SpotifyImportManager.authenticate], playlists as `PlaylistMeta` (id = uri). */
class SpotifyProvider : ImportProvider {
    override val id = ImportProviderId.SPOTIFY

    // Built on first use: Settings.shared is only safe after the backend is initialised.
    private val manager by lazy {
        // SpotifyImportManager(clientFactory: () -> SpotifyClient, loadStoredSession: () -> String? = { null }, saveStoredSession: (String) -> Unit = {})
        // SpotifyClient.create(SpotifyConfig(engine, logLevel, logger)); LogLevel.NONE keeps request details out of the logs.
        SpotifyImportManager(
            clientFactory = { SpotifyClient.create(SpotifyConfig(engine = sharedHttpEngine, logLevel = LogLevel.NONE)) },
            loadStoredSession = { Settings.shared.spotifySession },
            saveStoredSession = { Settings.shared.spotifySession = it },
        )
    }

    @Volatile
    private var listed: Map<String, PlaylistMeta> = emptyMap()

    override val signedIn: Boolean get() = manager.isAuthenticated || Settings.shared.spotifySession.isNotBlank()

    override suspend fun authenticate(
        login: WebLoginResult,
        amazonHost: String?,
    ) {
        // spotifySessionCookie(cookieHeader: String?): String?
        val spDc = spotifySessionCookie(login.header()) ?: noCredentials(id)
        // suspend fun authenticate(spDc: String)
        manager.authenticate(spDc)
    }

    override suspend fun listPlaylists(): List<ImportRow> {
        // suspend fun loadPlaylists(): List<PlaylistMeta>; it restores a stored session first
        val playlists = manager.loadPlaylists()
        listed = playlists.associateBy { it.uri }
        return playlists.map { ImportRow(it.uri, it.name, it.trackCount, it.image) }
    }

    override fun known(playlistId: String): ImportRow? = listed[playlistId]?.let { ImportRow(it.uri, it.name, it.trackCount, it.image) }

    override suspend fun load(playlistId: String): LoadedImport {
        val meta = listed[playlistId] ?: run { listPlaylists(); listed[playlistId] } ?: throw ApiException("bad_request", "unknown playlist")
        // suspend fun loadPlaylist(playlist: PlaylistMeta, onTrackProgress: ((Int, Int) -> Unit)? = null): LoadedPlaylist
        val loaded = manager.loadPlaylist(meta)
        return LoadedImport(loaded.name, loaded.tracks.map(CsvTrackMapper::fromSpotify), loaded.image)
    }
}

/** Tidal: `sid` + `userId` cookies, playlists as `TidalImportManager.LoadedPlaylist` (id = uuid, "favorites" for liked songs). */
class TidalProvider : ImportProvider {
    override val id = ImportProviderId.TIDAL
    private val manager = TidalImportManager()

    @Volatile
    private var listed: Map<String, TidalImportManager.LoadedPlaylist> = emptyMap()

    override val signedIn: Boolean get() = manager.isAuthenticated

    override suspend fun authenticate(
        login: WebLoginResult,
        amazonHost: String?,
    ) {
        // TidalImportManager.extractSessionCredentials(cookieHeader: String?): Pair<String, String>? (sessionId, userId)
        val (sessionId, userId) = TidalImportManager.extractSessionCredentials(login.header()) ?: noCredentials(id)
        // fun authenticate(sessionId: String, userId: String)
        manager.authenticate(sessionId, userId)
    }

    override suspend fun listPlaylists(): List<ImportRow> {
        // suspend fun loadPlaylists(likedSongsName: String = "Liked Songs"): List<LoadedPlaylist>
        val playlists = manager.loadPlaylists(LIKED_SONGS_NAME)
        listed = playlists.associateBy { it.id }
        return playlists.map(::row)
    }

    override fun known(playlistId: String): ImportRow? = listed[playlistId]?.let(::row)

    override suspend fun load(playlistId: String): LoadedImport {
        val playlist = listed[playlistId] ?: run { listPlaylists(); listed[playlistId] } ?: throw ApiException("bad_request", "unknown playlist")
        // suspend fun loadPlaylist(playlist: LoadedPlaylist, likedSongsName: String, onProgress: (Int, Int?) -> Unit): ImportedPlaylistData
        val data = manager.loadPlaylist(playlist, LIKED_SONGS_NAME) { _, _ -> }
        return loadedOf(data, playlist.image)
    }

    private fun row(p: TidalImportManager.LoadedPlaylist) = ImportRow(p.id, p.name, p.trackCount, p.image)
}

/** Deezer: `arl` cookie, playlists as `DeezerImportManager.LoadedPlaylist` ("liked" for liked songs). */
class DeezerProvider : ImportProvider {
    override val id = ImportProviderId.DEEZER
    private val manager = DeezerImportManager()

    @Volatile
    private var listed: Map<String, DeezerImportManager.LoadedPlaylist> = emptyMap()

    override val signedIn: Boolean get() = manager.isAuthenticated

    override suspend fun authenticate(
        login: WebLoginResult,
        amazonHost: String?,
    ) {
        // DeezerImportManager.extractArl(cookieHeader: String?): String?
        val arl = DeezerImportManager.extractArl(login.header()) ?: noCredentials(id)
        // fun authenticate(arl: String)
        manager.authenticate(arl)
    }

    override suspend fun listPlaylists(): List<ImportRow> {
        // suspend fun loadPlaylists(likedSongsName: String = "Liked Songs"): List<LoadedPlaylist>
        val playlists = manager.loadPlaylists(LIKED_SONGS_NAME)
        listed = playlists.associateBy { it.id }
        return playlists.map(::row)
    }

    override fun known(playlistId: String): ImportRow? = listed[playlistId]?.let(::row)

    override suspend fun load(playlistId: String): LoadedImport {
        val playlist = listed[playlistId] ?: run { listPlaylists(); listed[playlistId] } ?: throw ApiException("bad_request", "unknown playlist")
        // suspend fun loadPlaylist(playlist: LoadedPlaylist, likedSongsName: String, onProgress: (Int, Int?) -> Unit): ImportedPlaylistData
        val data = manager.loadPlaylist(playlist, LIKED_SONGS_NAME) { _, _ -> }
        return loadedOf(data, playlist.image)
    }

    private fun row(p: DeezerImportManager.LoadedPlaylist) = ImportRow(p.id, p.name, p.trackCount, p.image)
}

/** Amazon Music: session cookies of a marketplace host, playlists as `AmazonMusicImportManager.LoadedPlaylist`. */
class AmazonProvider : ImportProvider {
    override val id = ImportProviderId.AMAZON
    private val manager = AmazonMusicImportManager()

    @Volatile
    private var listed: Map<String, AmazonMusicImportManager.LoadedPlaylist> = emptyMap()

    override val signedIn: Boolean get() = manager.isAuthenticated

    override suspend fun authenticate(
        login: WebLoginResult,
        amazonHost: String?,
    ) {
        // AmazonMusicImportManager.extractSessionCookie(cookieHeader: String?): String? (returns the header itself when it has a session cookie)
        val cookies = AmazonMusicImportManager.extractSessionCookie(login.header()) ?: noCredentials(id)
        val host = ImportLoginConfigs.amazonHost(login.finalUrl, amazonHost ?: ImportLoginConfigs.DEFAULT_AMAZON_HOST)
        // fun authenticate(cookies: String, host: String); host must be in AmazonMusicPlaylistImporter.marketplaceHosts
        manager.authenticate(cookies, host)
    }

    override suspend fun listPlaylists(): List<ImportRow> {
        // suspend fun loadPlaylists(likedSongsName: String = "Liked Songs"): List<LoadedPlaylist>
        val playlists = manager.loadPlaylists(LIKED_SONGS_NAME)
        listed = playlists.associateBy { it.id }
        return playlists.map(::row)
    }

    override fun known(playlistId: String): ImportRow? = listed[playlistId]?.let(::row)

    override suspend fun load(playlistId: String): LoadedImport {
        val playlist = listed[playlistId] ?: run { listPlaylists(); listed[playlistId] } ?: throw ApiException("bad_request", "unknown playlist")
        // suspend fun loadPlaylist(playlist: LoadedPlaylist, likedSongsName: String, onProgress: (Int, Int?) -> Unit): ImportedPlaylistData
        val data = manager.loadPlaylist(playlist, LIKED_SONGS_NAME) { _, _ -> }
        return loadedOf(data, playlist.image)
    }

    private fun row(p: AmazonMusicImportManager.LoadedPlaylist) = ImportRow(p.id, p.name, p.trackCount, p.image)
}

/** Apple Music: `media-user-token` cookie kept in memory only; playlists as `PlaylistMeta` (id = `apple:playlist:<id>`). */
class AppleProvider : ImportProvider {
    override val id = ImportProviderId.APPLE

    @Volatile
    private var userToken: String? = null

    @Volatile
    private var listed: Map<String, PlaylistMeta> = emptyMap()

    override val signedIn: Boolean get() = userToken != null

    override suspend fun authenticate(
        login: WebLoginResult,
        amazonHost: String?,
    ) {
        // AppleMusicPlaylistImporter.extractAppleMusicUserToken(cookieHeader: String?): String?
        userToken = AppleMusicPlaylistImporter.extractAppleMusicUserToken(login.header()) ?: noCredentials(id)
    }

    override suspend fun listPlaylists(): List<ImportRow> {
        val token = userToken ?: throw ApiException("not_signed_in", "Sign in to Apple Music first")
        // suspend fun loadUserPlaylists(userToken: String): Result<List<PlaylistMeta>>
        val playlists = AppleMusicPlaylistImporter.loadUserPlaylists(token).getOrThrow()
        listed = playlists.associateBy { it.uri }
        return playlists.map { ImportRow(it.uri, it.name, it.trackCount, it.image) }
    }

    override fun known(playlistId: String): ImportRow? = listed[playlistId]?.let { ImportRow(it.uri, it.name, it.trackCount, it.image) }

    override suspend fun load(playlistId: String): LoadedImport {
        val token = userToken ?: throw ApiException("not_signed_in", "Sign in to Apple Music first")
        val meta = listed[playlistId] ?: run { listPlaylists(); listed[playlistId] } ?: throw ApiException("bad_request", "unknown playlist")
        // suspend fun loadUserPlaylistTracks(userToken: String, playlistUriOrId: String, playlistName: String = "", onProgress: (Int, Int?) -> Unit): Result<ImportedPlaylistData>
        val data = AppleMusicPlaylistImporter.loadUserPlaylistTracks(token, playlistId, meta.name) { _, _ -> }.getOrThrow()
        return loadedOf(data, meta.image)
    }
}

private fun loadedOf(
    data: ImportedPlaylistData,
    art: String?,
): LoadedImport = LoadedImport(data.title, data.tracks, data.coverUrl ?: art)

/** The five providers, built on first use. */
object ImportProviders {
    private val all: Map<ImportProviderId, ImportProvider> by lazy {
        listOf(SpotifyProvider(), TidalProvider(), DeezerProvider(), AppleProvider(), AmazonProvider()).associateBy { it.id }
    }

    operator fun get(id: ImportProviderId): ImportProvider = all.getValue(id)
}
