package com.metrolist.core.api.library

import com.metrolist.core.api.AlbumCard
import com.metrolist.core.api.Art
import com.metrolist.core.api.ArtistCard
import com.metrolist.core.api.ArtistRef
import com.metrolist.core.api.PlaylistCard
import com.metrolist.core.api.toJson
import com.metrolist.core.api.toTrack
import com.metrolist.core.seed.LibrarySeed
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.notFound
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.Album
import com.metrolist.shared.db.entities.Artist
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Playlist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSong
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.source.sourceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import org.koin.core.context.GlobalContext
import kotlin.time.Instant

internal val libraryDao: DatabaseDao get() = GlobalContext.get().get()
internal val libraryDatabase: MusicDatabase get() = GlobalContext.get().get()

/** Backend up, test seed (if any) finished, change watcher running. Every library handler starts here. */
suspend fun libraryReady() {
    backendReady()
    LibrarySeed.await()
    LibraryChanges.ensureStarted()
}

/** Entries of one revision, built once and shared by concurrent callers (single flight); older revisions are dropped. */
internal class RevisionCache<V : Any>(
    private val capacity: Int,
) {
    private val entries =
        object : LinkedHashMap<String, Pair<Long, V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, V>>?): Boolean = size > capacity
        }
    private val buildLock = Mutex()

    @Synchronized
    fun peek(
        key: String,
        revision: Long,
    ): V? = entries[key]?.takeIf { it.first == revision }?.second

    @Synchronized
    private fun put(
        key: String,
        revision: Long,
        value: V,
    ) {
        entries[key] = revision to value
    }

    suspend fun getOrBuild(
        key: String,
        revision: Long,
        build: suspend () -> V,
    ): V {
        peek(key, revision)?.let { return it }
        return buildLock.withLock { peek(key, revision) ?: build().also { put(key, revision, it) } }
    }
}

/** One sorted and filtered list: row ids (for `library.indexOf`) and a renderer for a slice of rows. */
internal class WindowList(
    val ids: List<String>,
    val groups: List<HistoryGroups.Group> = emptyList(),
    /** Content signature of a user album's entries (see [AlbumRevisionLog]). */
    val signature: Long = 0L,
    val slice: suspend (from: Int, to: Int) -> List<JsonElement>,
) {
    val size: Int get() = ids.size
}

/** Loads, filters, sorts and caches the lists behind `library.window` and `library.indexOf` (api.md 3.4). */
internal object LibraryLists {
    private val bases = RevisionCache<List<Song>>(8)
    private val others = RevisionCache<List<Any>>(8)
    private val sorted = RevisionCache<WindowList>(24)
    private val lastPlayed = RevisionCache<Map<String, Long>>(2)

    suspend fun get(
        ref: ListRef,
        sort: SortSpec,
        filter: FilterSpec,
        revision: Long,
    ): WindowList =
        sorted.getOrBuild("${ref.wire}|${sort.key}|${filter.key}", revision) {
            withContext(Dispatchers.IO) { build(ref, sort, filter, revision) }
        }

    private suspend fun build(
        ref: ListRef,
        sort: SortSpec,
        filter: FilterSpec,
        revision: Long,
    ): WindowList =
        when (ref.kind) {
            ListKind.SONGS, ListKind.LIKED, ListKind.DOWNLOADED, ListKind.CACHED -> songList(ref.kind, sort, filter, revision)
            ListKind.USER_ALBUM -> userAlbumList(ref.albumId!!, sort, filter)
            ListKind.ALBUMS -> albumList(sort, filter, revision)
            ListKind.ARTISTS -> artistList(sort, filter, revision)
            ListKind.PLAYLISTS -> playlistList(sort, filter, revision)
            ListKind.HISTORY -> HistoryData.list(sort, filter, revision)
        }

    // --- songs ---------------------------------------------------------------------------------------------------

    /** The unsorted rows of a song list in "added" order, oldest first; shared by every sort of the same revision. */
    suspend fun songBase(
        kind: ListKind,
        revision: Long,
    ): List<Song> =
        bases.getOrBuild(kind.wire, revision) {
            val dao = libraryDao
            when (kind) {
                ListKind.SONGS -> dao.songsByCreateDateAsc().first()
                ListKind.LIKED -> dao.likedSongsByCreateDateAsc().first()
                ListKind.DOWNLOADED -> dao.downloadedSongsByCreateDateAsc().first()
                ListKind.CACHED -> cachedSongs(dao)
                else -> emptyList()
            }
        }

    private suspend fun songList(
        kind: ListKind,
        sort: SortSpec,
        filter: FilterSpec,
        revision: Long,
    ): WindowList {
        val rows = SongRows.filter(songBase(kind, revision), filter) { it }
        val played = if (sort.by == "lastPlayed") lastPlayedMap(revision) else null
        val ordered = SongRows.sort(rows, sort, { it }, { id -> played?.get(id) ?: 0L })
        return WindowList(ids = ordered.map { it.song.id }) { from, to -> ordered.subList(from, to).map { it.toTrack().toJson() } }
    }

    suspend fun lastPlayedMap(revision: Long): Map<String, Long> =
        lastPlayed.getOrBuild("all", revision) {
            val map = HashMap<String, Long>()
            // The events come newest first: the first one seen per song is its last play.
            HistoryData.events(revision).forEach { map.putIfAbsent(it.songId, it.playedAt) }
            map
        }

    /** The cache manager's files as songs: the stored row when the database knows the id, else a bare one from the cache entry. */
    private suspend fun cachedSongs(dao: DatabaseDao): List<Song> {
        val entries = SongCacheManager.getCachedSongs().sortedBy { it.cachedAt }
        if (entries.isEmpty()) return emptyList()
        val stored = dao.getSongsByIds(entries.map { it.songId }).associateBy { it.song.id }
        return entries.map { entry ->
            stored[entry.songId]
                ?: Song(
                    song = SongEntity(id = entry.songId, title = entry.title, isCached = true),
                    artists = listOf(ArtistEntity(id = "LAcached", name = entry.artist, isLocal = true)),
                )
        }
    }

    // --- user albums ---------------------------------------------------------------------------------------------

    private suspend fun userAlbumList(
        albumId: String,
        sort: SortSpec,
        filter: FilterSpec,
    ): WindowList {
        val playlistId = LibraryDto.playlistIdOf(albumId)
        val dao = libraryDao
        val rows: List<PlaylistSong> = dao.playlistSongs(playlistId).first()
        if (rows.isEmpty() && dao.findPlaylistEntity(playlistId) == null) notFound("user album $albumId not found")
        val signature = AlbumRevisionLog.signature(rows.map { it.map.id })
        val filtered = SongRows.filter(rows, filter) { it.song }
        val ordered = SongRows.sort(filtered, sort, { it.song }, addedOrder = { it.map.id.toLong() })
        return WindowList(ids = ordered.map { it.song.song.id }, signature = signature) { from, to ->
            ordered.subList(from, to).map { LibraryDto.entryTrack(it).toJson() }
        }
    }

    // --- albums, artists, playlists ------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> otherBase(
        key: String,
        revision: Long,
        load: suspend () -> List<T>,
    ): List<T> = others.getOrBuild(key, revision) { load() } as List<T>

    private suspend fun albumList(
        sort: SortSpec,
        filter: FilterSpec,
        revision: Long,
    ): WindowList {
        val all = otherBase("albums", revision) { libraryDao.albumsByCreateDateAsc().first() }
        val rows = textFilter(all, filter) { listOf(it.album.title) + it.artists.map(ArtistEntity::name) }
        val ordered =
            when (sort.by) {
                "title" -> SongRows.sortedByKey(rows) { VnCollation.key(it.album.title) }
                "artist" -> SongRows.sortedByKey(rows) { VnCollation.key(it.artists.firstOrNull()?.name.orEmpty()) }
                "duration" -> SongRows.sortedByKey(rows) { it.album.duration.toLong() }
                else -> rows
            }.let { if (sort.desc) it.asReversed() else it }
        return WindowList(ids = ordered.map { it.album.id }) { from, to -> ordered.subList(from, to).map { LibraryDto.albumCard(it).toJson() } }
    }

    private suspend fun artistList(
        sort: SortSpec,
        filter: FilterSpec,
        revision: Long,
    ): WindowList {
        val all = otherBase("artists", revision) { libraryDao.artistsByCreateDateAsc().first() }
        val rows = textFilter(all, filter) { listOf(it.artist.name) }
        val ordered =
            (if (sort.by == "title") SongRows.sortedByKey(rows) { VnCollation.key(it.artist.name) } else rows)
                .let { if (sort.desc) it.asReversed() else it }
        return WindowList(ids = ordered.map { it.artist.id }) { from, to -> ordered.subList(from, to).map { LibraryDto.artistCard(it).toJson() } }
    }

    /** Saved playlists; user albums (local playlists marked as albums) have their own lists. */
    private suspend fun playlistList(
        sort: SortSpec,
        filter: FilterSpec,
        revision: Long,
    ): WindowList {
        val all = otherBase("playlists", revision) { libraryDao.playlistsByCreateDateAsc().first().filterNot { it.playlist.isUserAlbum } }
        val rows = textFilter(all, filter) { listOf(it.playlist.name) }
        val ordered =
            (if (sort.by == "title") SongRows.sortedByKey(rows) { VnCollation.key(it.playlist.name) } else rows)
                .let { if (sort.desc) it.asReversed() else it }
        return WindowList(ids = ordered.map { LibraryDto.playlistCardId(it.playlist) }) { from, to ->
            ordered.subList(from, to).map { LibraryDto.playlistCard(it).toJson() }
        }
    }

    private fun <T> textFilter(
        rows: List<T>,
        filter: FilterSpec,
        texts: (T) -> List<String>,
    ): List<T> {
        val wanted = filter.text ?: return rows
        return rows.filter { row -> texts(row).any { VnCollation.fold(it).contains(wanted) } }
    }

    // --- counts --------------------------------------------------------------------------------------------------

    suspend fun counts(revision: Long): LibraryCounts =
        withContext(Dispatchers.IO) {
            val dao = libraryDao
            LibraryCounts(
                songs = songBase(ListKind.SONGS, revision).size,
                liked = dao.likedSongsCount().first(),
                downloaded = songBase(ListKind.DOWNLOADED, revision).size,
                albums = otherBase("albums", revision) { dao.albumsByCreateDateAsc().first() }.size,
                artists = otherBase("artists", revision) { dao.artistsByCreateDateAsc().first() }.size,
                playlists = otherBase("playlists", revision) { dao.playlistsByCreateDateAsc().first().filterNot { it.playlist.isUserAlbum } }.size,
                userAlbums = dao.userAlbums().first().size,
            )
        }
}

data class LibraryCounts(
    val songs: Int,
    val liked: Int,
    val downloaded: Int,
    val albums: Int,
    val artists: Int,
    val playlists: Int,
    val userAlbums: Int,
)

/** Pure mapping of library rows to the api.md card shapes. */
internal object LibraryDto {
    const val USER_ALBUM_PREFIX = "lp:"

    fun userAlbumId(playlistId: String): String = USER_ALBUM_PREFIX + playlistId

    fun playlistIdOf(userAlbumId: String): String = userAlbumId.removePrefix(USER_ALBUM_PREFIX)

    /** `core:cover/<id>` for a stored cover file, the URL itself for a remote one, else the plain thumbnail. */
    fun art(
        customThumbnailUrl: String?,
        thumbnailUrl: String?,
    ): Art? {
        CoverFiles.idOfStoredCover(customThumbnailUrl)?.let { return Art(src = "core:cover/$it") }
        val url = customThumbnailUrl?.takeIf { it.startsWith("http") } ?: thumbnailUrl
        return url?.takeIf { it.isNotBlank() }?.let { Art(src = it) }
    }

    fun albumCard(album: Album): AlbumCard =
        AlbumCard(
            id = album.album.id,
            title = album.album.title,
            artists = album.artists.map { ArtistRef(if (it.isLocal) null else it.id, it.name) },
            year = album.album.year,
            art = album.album.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { Art(src = it) },
            source = sourceOf(album.album.id).key,
            kind = "album",
        )

    fun artistCard(artist: Artist): ArtistCard =
        ArtistCard(
            id = artist.artist.id,
            name = artist.artist.name,
            art = artist.artist.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { Art(src = it) },
            source = sourceOf(artist.artist.id).key,
        )

    fun playlistCardId(entity: PlaylistEntity): String = entity.browseId?.takeIf { !entity.isLocal } ?: userAlbumId(entity.id)

    fun playlistCard(playlist: Playlist): PlaylistCard {
        val entity = playlist.playlist
        val local = entity.isLocal || entity.browseId == null
        val id = playlistCardId(entity)
        return PlaylistCard(
            id = id,
            title = entity.name,
            art = art(entity.customThumbnailUrl, entity.thumbnailUrl),
            source = if (local) "local" else sourceOf(id).key,
            local = local,
            count = playlist.songCount,
        )
    }

    fun userAlbumCard(
        entity: PlaylistEntity,
        count: Int,
    ): PlaylistCard =
        PlaylistCard(
            id = userAlbumId(entity.id),
            title = entity.name,
            art = art(entity.customThumbnailUrl, entity.thumbnailUrl),
            source = "local",
            local = true,
            count = count,
        )

    /** A user album's song with its entry id (`PlaylistSongMap.id`) in `setVideoId`. */
    fun entryTrack(entry: PlaylistSong) = entry.song.toTrack().copy(setVideoId = entry.map.id.toString())

    /** Epoch milliseconds as the UTC `LocalDateTime` the database stores. */
    fun utc(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
}
