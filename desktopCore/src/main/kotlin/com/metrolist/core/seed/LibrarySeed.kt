package com.metrolist.core.seed

import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreLog
import com.metrolist.core.api.library.LibraryChanges
import com.metrolist.core.api.library.LibraryDto
import com.metrolist.core.prefs.CorePrefs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.HistorySnapshot
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import java.io.File

// The file format of `--seed-library` (tests/fixtures/library-seed.json, written by M1-5); unknown keys are ignored.

@Serializable
data class SeedArtistRef(
    val id: String? = null,
    val name: String,
)

@Serializable
data class SeedAlbumRef(
    val id: String? = null,
    val title: String,
)

@Serializable
data class SeedSong(
    val id: String,
    val title: String,
    val artists: List<SeedArtistRef> = emptyList(),
    val album: SeedAlbumRef? = null,
    val durationMs: Long = 0,
    val thumbnailUrl: String? = null,
    val liked: Boolean = false,
    val addedAt: Long = 0,
    val plays: Long = 0,
)

@Serializable
data class SeedAlbum(
    val id: String,
    val title: String,
    val artists: List<SeedArtistRef> = emptyList(),
    val year: Int? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
data class SeedArtist(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
)

@Serializable
data class SeedUserAlbum(
    val title: String,
    val songIds: List<String> = emptyList(),
    val pinned: Boolean = false,
)

@Serializable
data class SeedHistory(
    val songId: String,
    val playedAt: Long,
    val playTimeMs: Long = 0,
)

@Serializable
data class SeedData(
    val songs: List<SeedSong> = emptyList(),
    val albums: List<SeedAlbum> = emptyList(),
    val artists: List<SeedArtist> = emptyList(),
    val userAlbums: List<SeedUserAlbum> = emptyList(),
    val history: List<SeedHistory> = emptyList(),
)

/** The rows a seed file turns into, built without a database so the mapping can be tested. */
class SeedPlan(
    val artists: List<ArtistEntity>,
    val albums: List<AlbumEntity>,
    val albumArtists: List<AlbumArtistMap>,
    val songs: List<SongEntity>,
    val songArtists: List<SongArtistMap>,
    val songAlbums: List<SongAlbumMap>,
    val userAlbums: List<PlannedUserAlbum>,
    val history: List<PlannedPlay>,
    /** Liked songs, newest like first, for [SongLikeState]. */
    val likes: List<SongItem>,
    /** One saved (non user-album) playlist, so `library.counts.playlists >= 1` holds on a seeded root. */
    val playlists: List<PlannedUserAlbum> = emptyList(),
) {
    class PlannedUserAlbum(
        val entity: PlaylistEntity,
        val songIds: List<String>,
        val pinned: Boolean,
    )

    class PlannedPlay(
        val event: Event,
        val snapshot: HistorySnapshot,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): SeedData = json.decodeFromString(text)

        /** An artist without an id gets a stable local one, so the same name is one artist. */
        fun artistId(ref: SeedArtistRef): String = ref.id?.takeIf { it.isNotBlank() } ?: ("LAseed" + ref.name.hashCode().toUInt().toString(16))

        fun of(
            data: SeedData,
            nowMs: Long,
        ): SeedPlan {
            val songs = data.songs.distinctBy { it.id }
            val songIds = songs.mapTo(HashSet()) { it.id }

            // An artist reference without an id (album artists in the seed file) means the artist of that name.
            val idByName = HashMap<String, String>()
            data.artists.forEach { idByName.putIfAbsent(it.name, it.id) }
            songs.forEach { song -> song.artists.forEach { ref -> ref.id?.takeIf(String::isNotBlank)?.let { idByName.putIfAbsent(ref.name, it) } } }

            val artists = LinkedHashMap<String, ArtistEntity>()

            fun addArtist(
                ref: SeedArtistRef,
                thumbnail: String?,
            ): String {
                val known = ref.id?.takeIf { it.isNotBlank() } ?: idByName[ref.name]
                val id = known ?: artistId(ref)
                val existing = artists[id]
                artists[id] =
                    ArtistEntity(
                        id = id,
                        name = existing?.name ?: ref.name,
                        thumbnailUrl = existing?.thumbnailUrl ?: thumbnail,
                        isLocal = known == null,
                    )
                return id
            }
            data.artists.forEach { addArtist(SeedArtistRef(it.id, it.name), it.thumbnailUrl) }

            val albumIds = data.albums.mapTo(HashSet()) { it.id }
            val songsOfAlbum = songs.filter { it.album?.id != null }.groupBy { it.album!!.id!! }
            val albumArtists = ArrayList<AlbumArtistMap>()
            val albums =
                data.albums.distinctBy { it.id }.map { album ->
                    album.artists.forEachIndexed { order, ref -> albumArtists += AlbumArtistMap(album.id, addArtist(ref, null), order) }
                    val inAlbum = songsOfAlbum[album.id].orEmpty()
                    AlbumEntity(
                        id = album.id,
                        title = album.title,
                        year = album.year,
                        thumbnailUrl = album.thumbnailUrl,
                        songCount = inAlbum.size,
                        duration = inAlbum.sumOf { it.durationMs / 1000 }.toInt(),
                        lastUpdateTime = LibraryDto.utc(nowMs),
                    )
                }

            val songArtists = ArrayList<SongArtistMap>()
            val songAlbums = ArrayList<SongAlbumMap>()
            val nextIndex = HashMap<String, Int>()
            val entities =
                songs.map { song ->
                    song.artists.forEachIndexed { position, ref -> songArtists += SongArtistMap(song.id, addArtist(ref, null), position) }
                    val albumId = song.album?.id
                    if (albumId != null && albumId in albumIds) {
                        val index = nextIndex.merge(albumId, 1, Int::plus)!! - 1
                        songAlbums += SongAlbumMap(song.id, albumId, index)
                    }
                    SongEntity(
                        id = song.id,
                        title = song.title,
                        duration = if (song.durationMs > 0) (song.durationMs / 1000).toInt() else -1,
                        thumbnailUrl = song.thumbnailUrl,
                        albumId = albumId,
                        albumName = song.album?.title,
                        liked = song.liked,
                        likedDate = if (song.liked) LibraryDto.utc(song.addedAt) else null,
                        totalPlayTime = song.plays * 60_000L,
                        inLibrary = LibraryDto.utc(song.addedAt),
                    )
                }

            val userAlbums =
                data.userAlbums.mapIndexed { index, album ->
                    val ids = album.songIds.filter { it in songIds }.distinct()
                    val thumbnail = ids.firstNotNullOfOrNull { id -> songs.firstOrNull { it.id == id }?.thumbnailUrl }
                    val created = LibraryDto.utc(nowMs - index * 1_000L)
                    PlannedUserAlbum(
                        PlaylistEntity(
                            name = album.title,
                            createdAt = created,
                            lastUpdateTime = created,
                            bookmarkedAt = created,
                            thumbnailUrl = thumbnail,
                            isLocal = true,
                            isUserAlbum = true,
                        ),
                        ids,
                        album.pinned,
                    )
                }

            val byId = songs.associateBy { it.id }
            val history =
                data.history.mapNotNull { play ->
                    val song = byId[play.songId] ?: return@mapNotNull null
                    val names = song.artists.map { it.name.trim() }
                    PlannedPlay(
                        Event(
                            songId = song.id,
                            timestamp = LibraryDto.utc(play.playedAt),
                            playTime = play.playTimeMs,
                            playTimeKnown = true,
                            source = "LOCAL",
                        ),
                        // The event id is only known after the insert; the snapshot is re-keyed then.
                        HistorySnapshot(
                            eventId = 0,
                            title = song.title,
                            artists = names.joinToString(", "),
                            album = song.album?.title,
                            duration = song.durationMs / 1000,
                            thumbnailUrl = song.thumbnailUrl,
                            artistNamesJson = Json.encodeToString(names),
                            albumId = song.album?.id?.takeIf { it.isNotBlank() },
                        ),
                    )
                }

            val likes =
                songs.filter { it.liked }.sortedByDescending { it.addedAt }.map { song ->
                    SongItem(
                        id = song.id,
                        title = song.title,
                        thumbnail = song.thumbnailUrl,
                        artists = song.artists.map { ArtistTiny(it.id?.takeIf(String::isNotBlank), it.name) },
                        album = song.album?.let { AlbumTiny(it.id, it.title) },
                        duration = song.durationMs / 1000,
                    )
                }

            val playlists =
                userAlbums.firstOrNull()?.let { first ->
                    val created = LibraryDto.utc(nowMs - userAlbums.size * 1_000L)
                    listOf(
                        PlannedUserAlbum(
                            PlaylistEntity(
                                name = "Seed playlist",
                                createdAt = created,
                                lastUpdateTime = created,
                                bookmarkedAt = created,
                                thumbnailUrl = first.entity.thumbnailUrl,
                                isLocal = true,
                            ),
                            first.songIds,
                            false,
                        ),
                    )
                }.orEmpty()

            return SeedPlan(artists.values.toList(), albums, albumArtists, entities, songArtists, songAlbums, userAlbums, history, likes, playlists)
        }
    }
}

/**
 * `--seed-library <file>` (test only): fills an EMPTY library from a JSON file through DAO calls once the backend is up.
 * Library handlers wait for it ([await]). It is logged at INFO; without the flag nothing happens.
 */
object LibrarySeed {
    @Volatile
    private var pending: Deferred<Unit>? = null

    fun schedule(path: String?) {
        if (path.isNullOrBlank()) return
        pending =
            CoreBackend.scope.async(Dispatchers.IO) {
                try {
                    CoreBackend.await()
                    seedFrom(File(path))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    CoreLog.error("seed-library from $path failed", error)
                }
            }
    }

    suspend fun await() {
        pending?.await()
    }

    private suspend fun seedFrom(file: File) {
        val database = GlobalContext.get().get<MusicDatabase>()
        val dao = database.dao()
        if (dao.songLibraryMemberships().first().isNotEmpty()) {
            CoreLog.info("seed-library: the library already has songs, $file ignored")
            return
        }
        val begin = System.nanoTime()
        val plan = SeedPlan.of(SeedPlan.parse(file.readText(Charsets.UTF_8)), System.currentTimeMillis())
        database.withDatabaseTransaction {
            dao.upsertArtistEntities(plan.artists)
            dao.upsertAlbumEntities(plan.albums)
            dao.upsertAlbumArtists(plan.albumArtists)
            dao.upsertSongEntities(plan.songs)
            dao.upsertSongArtists(plan.songArtists)
            plan.songAlbums.forEach { dao.upsert(it) }
            for (album in plan.userAlbums + plan.playlists) {
                dao.insert(album.entity)
                album.songIds.forEachIndexed { position, songId ->
                    dao.insert(PlaylistSongMap(playlistId = album.entity.id, songId = songId, position = position))
                }
            }
            for (play in plan.history) {
                val eventId = dao.insert(play.event)
                if (eventId > 0) dao.insert(play.snapshot.copy(eventId = eventId))
            }
        }
        plan.userAlbums.filter { it.pinned }.forEach { CorePrefs.shared.setPinned(LibraryDto.userAlbumId(it.entity.id), true) }
        // Likes live in SongLikeState (settings) in the v1 app; seed them there too, without queueing any YouTube vote.
        if (plan.likes.isNotEmpty()) SongLikeState.replaceLikesLocally(plan.likes)
        LibraryChanges.bump(LibraryChanges.ALL_LISTS, userAlbums = true)
        CoreLog.info(
            "seed-library: ${plan.songs.size} songs, ${plan.albums.size} albums, ${plan.artists.size} artists, " +
                "${plan.userAlbums.size} user albums, ${plan.history.size} plays in ${(System.nanoTime() - begin) / 1_000_000} ms",
        )
    }
}
