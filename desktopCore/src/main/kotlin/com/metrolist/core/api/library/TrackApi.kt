package com.metrolist.core.api.library

import com.metrolist.core.api.PlayerApi
import com.metrolist.core.api.Quality
import com.metrolist.core.api.Track
import com.metrolist.core.api.pages.PageMappers
import com.metrolist.core.api.pages.loadAlbum
import com.metrolist.core.api.queue.QueueMapper
import com.metrolist.core.api.toJson
import com.metrolist.core.api.toTrack
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.notFound
import com.metrolist.core.server.requireBool
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.unsupported
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.toPlaylistItem
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.setStoredPlaylistBookmarked
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.AlbumLikeState
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.setArtistSubscribed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.resume

/** api.md 3.4: likes, saves, track details and sources, blocks; `import.files` is answered `unsupported` until M4-2. */
object TrackApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "track.like" to handler { params, _ -> like(params.requireStr("id"), params.requireBool("liked")) },
            "album.save" to handler { params, _ -> albumSave(params.requireStr("id"), params.requireBool("on")) },
            "artist.subscribe" to handler { params, _ -> artistSubscribe(params.requireStr("id"), params.requireBool("on")) },
            "playlist.save" to handler { params, _ -> playlistSave(params.requireStr("id"), params.requireBool("on")) },
            "track.info" to handler { params, _ -> info(params.requireStr("id")) },
            "track.sources" to handler { params, _ -> sources(params.requireStr("id")) },
            "track.setSource" to handler { _, _ -> unsupported("choosing a source per track arrives with M3-2") },
            "track.block" to handler { params, _ -> blockTrack(params.requireStr("id"), params.requireBool("on")) },
            "artist.block" to handler { params, _ -> blockArtist(params.requireStr("id"), params.requireBool("on")) },
        ) + importFilesStub()

    /**
     * `import.files` is answered `unsupported` until M4-2 lands its own module (the brief for this package); once that
     * module is on the class path the stub steps aside, because a method defined twice stops the core from starting.
     */
    private fun importFilesStub(): Map<String, Handler> {
        val owned = runCatching { Class.forName("com.metrolist.core.api.importfiles.ImportFilesModule", false, TrackApi::class.java.classLoader) }.isSuccess
        return if (owned) emptyMap() else mapOf("import.files" to handler { _, _ -> unsupported("file import arrives with M4-2") })
    }

    /** The stored song, else YouTube metadata; an id nobody knows is `not_found`. */
    private suspend fun resolveOne(id: String): SongItem {
        val song = PlayerApi.resolve(listOf(id)).first()
        if (QueueMapper.isUnresolved(song)) notFound("track $id not found")
        return song
    }

    // --- like / block ---------------------------------------------------------------------------------------------

    private suspend fun like(
        id: String,
        liked: Boolean,
    ): JsonElement {
        libraryReady()
        val song = resolveOne(id)
        // SongLikeState is the v1 source of truth for likes (settings-backed); the DB flag is kept in step for the lists.
        withContext(Dispatchers.Main) { if (liked) SongLikeState.addLike(song) else SongLikeState.removeLike(id) }
        withContext(Dispatchers.IO) { LikeMirror.set(song, liked) }
        LibraryChanges.bump(listOf("liked", "songs"))
        return buildJsonObject { put("liked", liked) }
    }

    private suspend fun blockTrack(
        id: String,
        on: Boolean,
    ): JsonElement {
        libraryReady()
        val song = resolveOne(id)
        withContext(Dispatchers.Main) { if (on) SongLikeState.dislike(song) else SongLikeState.removeDislike(id) }
        if (on) withContext(Dispatchers.IO) { LikeMirror.set(song, false) }
        LibraryChanges.bump(listOf("songs", "liked"))
        return emptyResult()
    }

    private suspend fun blockArtist(
        id: String,
        on: Boolean,
    ): JsonElement {
        libraryReady()
        withContext(Dispatchers.IO) {
            if (on) {
                val artist = libraryDao.getArtistById(id)
                BlockedContent.blockArtist(id, artist?.name ?: id, artist?.thumbnailUrl)
            } else {
                BlockedContent.unblockArtist(id)
            }
        }
        LibraryChanges.bump(listOf("songs", "artists"))
        return emptyResult()
    }

    // --- album / artist / playlist saves --------------------------------------------------------------------------

    private suspend fun albumSave(
        id: String,
        on: Boolean,
    ): JsonElement {
        libraryReady()
        val stored = withContext(Dispatchers.IO) { libraryDao.album(id).first() }
        if ((stored?.album?.bookmarkedAt != null) != on) {
            val (item, songs) = albumOf(id, stored?.album, stored?.artists.orEmpty())
            val result =
                suspendCancellableCoroutine<Result<Boolean>> { continuation ->
                    // toggleAlbumLike(album, songs, onResult): flips the saved state; it is only called when the state differs from [on].
                    AlbumLikeState.toggleAlbumLike(item, songs) { continuation.resume(it) }
                }
            result.getOrElse { throw ApiException("internal", "could not change the saved state of album $id: ${it.message}", retryable = true) }
            LibraryChanges.bump(listOf("albums"))
        }
        return buildJsonObject { put("on", on) }
    }

    private suspend fun albumOf(
        id: String,
        stored: AlbumEntity?,
        artists: List<ArtistEntity>,
    ): Pair<AlbumItem, List<SongItem>> {
        if (stored != null) {
            val songs = withContext(Dispatchers.IO) { libraryDao.albumSongs(id).first().map { it.toSongItem() } }
            val item =
                AlbumItem(
                    id = stored.id,
                    title = stored.title,
                    thumbnail = stored.thumbnailUrl,
                    artists = artists.map { ArtistTiny(if (it.isLocal) null else it.id, it.name, it.thumbnailUrl) },
                    year = stored.year?.toString(),
                    playlistId = stored.playlistId,
                    isExplicit = stored.explicit,
                    songCount = stored.songCount,
                )
            return item to songs
        }
        val page = loadAlbum(id) ?: notFound("album $id not found")
        return page.album to page.songs
    }

    private suspend fun artistSubscribe(
        id: String,
        on: Boolean,
    ): JsonElement {
        libraryReady()
        val stored = withContext(Dispatchers.IO) { libraryDao.getArtistById(id) }
        if ((stored?.bookmarkedAt != null) != on) {
            var name = stored?.name
            var thumbnail = stored?.thumbnailUrl
            if (name == null) {
                try {
                    // getArtist(channelId): ArtistPageResult; its header section carries the name.
                    val header = PageMappers.artistHeader(GlobalYouTubeRepository.instance.getArtist(id))
                    name = header?.title
                    thumbnail = header?.thumbnail
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Subscribe by id; the name is filled in when the artist is stored with a song.
                }
            }
            val done =
                withContext(Dispatchers.IO) {
                    // MusicDatabase.setArtistSubscribed(repository, artistId, artistName, subscribed, thumbnailUrl): Boolean
                    libraryDatabase.setArtistSubscribed(GlobalYouTubeRepository.instance, id, name ?: id, on, thumbnail)
                }
            if (!done) throw ApiException("network", "YouTube refused to change the subscription of $id", retryable = true)
            LibraryChanges.bump(listOf("artists"))
        }
        return buildJsonObject { put("on", on) }
    }

    private suspend fun playlistSave(
        id: String,
        on: Boolean,
    ): JsonElement {
        libraryReady()
        val dao = libraryDao
        val entity = withContext(Dispatchers.IO) { dao.findPlaylistEntity(LibraryDto.playlistIdOf(id)) }
        if ((entity?.bookmarkedAt != null) != on) {
            val repository = GlobalYouTubeRepository.instance
            val item: PlaylistItem
            val count: Int
            if (entity != null) {
                count = withContext(Dispatchers.IO) { dao.playlistSongMaps(entity.id, Int.MIN_VALUE).size }
                item = entity.toPlaylistItem(count)
            } else {
                // getPlaylistPages(playlistId, applyContentFilters): Flow<PlaylistPage>; the first page has the header.
                val page = repository.getPlaylistPages(id, false).first()
                item = page.sections["header"]?.firstOrNull() as? PlaylistItem ?: notFound("playlist $id not found")
                count = item.songCount?.filter(Char::isDigit)?.toIntOrNull() ?: page.songs.size
            }
            // setStoredPlaylistBookmarked(database, repository, id, playlist, bookmarked, songCount): Result<Unit>
            setStoredPlaylistBookmarked(libraryDatabase, repository, item.id, item, on, count).getOrElse {
                throw ApiException("internal", "could not change the saved state of playlist $id: ${it.message}", retryable = true)
            }
            LibraryChanges.bump(listOf("playlists"))
        }
        return buildJsonObject { put("on", on) }
    }

    // --- details ---------------------------------------------------------------------------------------------------

    private suspend fun info(id: String): JsonElement {
        libraryReady()
        val song = withContext(Dispatchers.IO) { libraryDao.getSongById(id) } ?: notFound("track $id not found")
        val format = song.format
        val track = song.toTrack().copy(quality = QualityLabel.of(format?.codecs, format?.bitrate))
        val path = SongCacheManager.getCachedFilePath(id)
        val size = path?.let { File(it).length() }?.takeIf { it > 0 } ?: format?.contentLength?.takeIf { it > 0 }
        val details =
            buildJsonObject {
                format?.codecs?.takeIf { it.isNotBlank() }?.let { put("codec", it) }
                format?.bitrate?.takeIf { it > 0 }?.let { put("bitrate", it / 1000) }
                format?.sampleRate?.let { put("sampleRate", it) }
                format?.loudnessDb?.let { put("loudnessDb", it) }
                size?.let { put("fileSize", it) }
                path?.let { put("path", it) }
                put("sources", JsonArray(listOf(sourceOption(track))))
            }
        return buildJsonObject {
            put("track", track.toJson())
            put("details", details)
        }
    }

    /** M1 stub: the current source only (the full per-track choice is M3-2). */
    private suspend fun sources(id: String): JsonElement {
        libraryReady()
        val song = withContext(Dispatchers.IO) { libraryDao.getSongById(id) } ?: notFound("track $id not found")
        val track = song.toTrack().copy(quality = QualityLabel.of(song.format?.codecs, song.format?.bitrate))
        return buildJsonObject { put("options", JsonArray(listOf(sourceOption(track)))) }
    }

    private fun sourceOption(track: Track): JsonObject =
        buildJsonObject {
            put("source", track.source)
            put("id", track.id)
            put("quality", (track.quality ?: Quality("Auto", false)).toJson())
            put("auto", true)
            put("available", true)
        }
}

/** Quality badge from a stored format; pure. */
object QualityLabel {
    fun of(
        codecs: String?,
        bitrateBps: Int?,
    ): Quality {
        val codec = codecs.orEmpty().lowercase()
        val kbps = bitrateBps?.takeIf { it > 0 }?.let { (it + 500) / 1000 }
        return when {
            "flac" in codec -> Quality("FLAC", true, kbps)
            "alac" in codec -> Quality("Lossless", true, kbps)
            "opus" in codec -> Quality(listOfNotNull("Opus", kbps).joinToString(" "), false, kbps)
            "mp4a" in codec || "aac" in codec -> Quality(listOfNotNull("AAC", kbps).joinToString(" "), false, kbps)
            "mp3" in codec || "mpeg" in codec -> Quality(kbps?.toString() ?: "MP3", false, kbps)
            else -> Quality("Auto", false, kbps)
        }
    }
}

/** Keeps the DB `liked` flag (what the lists read) in step with [SongLikeState] (what the v1 app stores in settings). */
object LikeMirror {
    suspend fun set(
        song: SongItem,
        liked: Boolean,
    ) {
        val dao = libraryDao
        if (dao.getSongById(song.id) == null) {
            if (!liked) return
            dao.upsertHistorySong(song)
        }
        val entity = dao.getSongById(song.id)?.song ?: return
        if (entity.liked != liked) {
            dao.update(entity.copy(liked = liked, likedDate = if (liked) LibraryDto.utc(System.currentTimeMillis()) else null))
        }
    }

    /** After a remote likes refresh: the DB flags follow [SongLikeState] (adds and removals). */
    suspend fun sync() {
        val dao = libraryDao
        val likedIds = SongLikeState.likedSongIds
        val flagged = dao.likedSongsByCreateDateAsc().first()
        flagged.filter { it.song.id !in likedIds }.forEach { dao.update(it.song.copy(liked = false, likedDate = null)) }
        val flaggedIds = flagged.mapTo(HashSet()) { it.song.id }
        SongLikeState.likedSongs.filter { it.id !in flaggedIds }.forEach { set(it, true) }
    }
}
