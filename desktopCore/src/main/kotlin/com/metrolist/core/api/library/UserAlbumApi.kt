package com.metrolist.core.api.library

import com.metrolist.core.api.Art
import com.metrolist.core.api.LibraryApi
import com.metrolist.core.api.PlayerApi
import com.metrolist.core.api.queue.QueueMapper
import com.metrolist.core.api.toJson
import com.metrolist.core.prefs.CorePrefs
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.bool
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.int
import com.metrolist.core.server.long
import com.metrolist.core.server.notFound
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.addSongsToLocalPlaylist
import com.metrolist.shared.playlist.createUserAlbum
import com.metrolist.shared.playlist.deleteStoredPlaylist
import com.metrolist.shared.playlist.moveSongInLocalPlaylist
import com.metrolist.shared.playlist.removeSongFromLocalPlaylist
import com.metrolist.shared.playlist.removeStoredPlaylistCover
import com.metrolist.shared.playlist.renameStoredPlaylist
import com.metrolist.shared.playlist.setStoredPlaylistCover
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * api.md 3.4 `userAlbum.*`: local playlists marked as user albums, ids `lp:<playlist id>`. Writes go through the shared
 * PlaylistMutations, then bump the library revision so windows and `library.changed` follow.
 */
object UserAlbumApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "userAlbum.create" to handler { params, _ -> create(params) },
            "userAlbum.rename" to handler { params, _ -> rename(params) },
            "userAlbum.delete" to handler { params, _ -> delete(params.requireStr("id")) },
            "userAlbum.setCover" to handler { params, _ -> setCover(params) },
            "userAlbum.add" to handler { params, _ -> add(params) },
            "userAlbum.remove" to handler { params, _ -> remove(params) },
            "userAlbum.move" to handler { params, _ -> move(params) },
            "userAlbum.pin" to handler { params, _ -> pin(params) },
            "userAlbum.list" to handler { _, _ -> list() },
        )

    /** Check-then-write sequences (add, remove, move) must not interleave. */
    private val writeLock = Mutex()

    private val repository get() = GlobalYouTubeRepository.instance

    private fun changed(albumId: String): Long = LibraryChanges.bump(listOf("userAlbum:$albumId"), userAlbums = true)

    /** The local playlist behind [albumId]; anything else is `not_found`. */
    private suspend fun requireEntity(albumId: String): PlaylistEntity {
        val playlistId = LibraryDto.playlistIdOf(albumId)
        val entity = withContext(Dispatchers.IO) { libraryDao.findPlaylistEntity(playlistId) }
        if (entity == null || !entity.isLocal) notFound("user album $albumId not found")
        return entity
    }

    private fun <T> Result<T>.orApi(): T =
        getOrElse { error ->
            throw when (error) {
                is CancellationException -> error
                is ApiException -> error
                is NoSuchElementException -> ApiException("not_found", error.message ?: "not found")
                is IllegalArgumentException -> ApiException("bad_request", error.message ?: "bad request")
                else -> ApiException("internal", error.message ?: error::class.java.simpleName)
            }
        }

    private fun stringList(
        params: JsonObject,
        key: String,
    ): List<String> =
        (params[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: badRequest("'$key' must hold strings") }
            ?: emptyList()

    /** The songs for [ids]: stored rows, else YouTube metadata; an unknown YouTube id is `not_found`. */
    private suspend fun songsOf(ids: List<String>): List<SongItem> {
        if (ids.isEmpty()) return emptyList()
        val songs = PlayerApi.resolve(ids, overrides = false)
        songs.firstOrNull { QueueMapper.isUnresolved(it) }?.let { notFound("track ${it.id} not found") }
        return songs
    }

    // --- create / rename / delete ----------------------------------------------------------------------------------

    private suspend fun create(params: JsonObject): JsonElement {
        libraryReady()
        val title = params.requireStr("title").trim()
        if (title.isEmpty()) badRequest("title must not be empty")
        val songs = songsOf(stringList(params, "trackIds"))
        val entity =
            if (songs.isEmpty()) {
                // createUserAlbum needs at least one song; an empty album is just the marked local playlist.
                PlaylistEntity(name = title, isLocal = true, isUserAlbum = true).also { libraryDao.insert(it) }
            } else {
                // createUserAlbum(database, name, songs): Result<PlaylistEntity>
                createUserAlbum(libraryDatabase, title, songs).orApi()
            }
        val id = LibraryDto.userAlbumId(entity.id)
        changed(id)
        return buildJsonObject { put("id", id) }
    }

    private suspend fun rename(params: JsonObject): JsonElement {
        libraryReady()
        val albumId = params.requireStr("id")
        val title = params.requireStr("title").trim()
        if (title.isEmpty()) badRequest("title must not be empty")
        val entity = requireEntity(albumId)
        // renameStoredPlaylist(database, repository, id, name): Result<Unit>
        renameStoredPlaylist(libraryDatabase, repository, entity.id, title).orApi()
        changed(albumId)
        return emptyResult()
    }

    private suspend fun delete(albumId: String): JsonElement {
        libraryReady()
        val entity = requireEntity(albumId)
        // deleteStoredPlaylist(database, repository, id): Result<Unit>
        deleteStoredPlaylist(libraryDatabase, repository, entity.id).orApi()
        CoverFiles.removeCovers(entity.id)
        CorePrefs.shared.setPinned(albumId, false)
        changed(albumId)
        return emptyResult()
    }

    // --- cover -----------------------------------------------------------------------------------------------------

    private suspend fun setCover(params: JsonObject): JsonElement {
        libraryReady()
        val albumId = params.requireStr("id")
        val path = params.str("path")
        val entity = requireEntity(albumId)
        val art =
            withContext(Dispatchers.IO) {
                if (path == null) {
                    // removeStoredPlaylistCover(database, repository, id, playlist = null): Result<String?>
                    removeStoredPlaylistCover(libraryDatabase, repository, entity.id).orApi()
                    CoverFiles.removeCovers(entity.id)
                    val fresh = libraryDao.findPlaylistEntity(entity.id) ?: entity
                    LibraryDto.art(fresh.customThumbnailUrl, fresh.thumbnailUrl)
                        ?: libraryDao.playlistPreviewThumbnails(entity.id).firstOrNull()?.let { Art(src = it) }
                        ?: Art(src = "core:cover/${entity.id}-none")
                } else {
                    val stored = CoverFiles.store(entity.id, File(path))
                    // setStoredPlaylistCover(database, repository, id, coverUrl, playlist = null): Result<Unit>
                    setStoredPlaylistCover(libraryDatabase, repository, entity.id, stored.file.absolutePath).orApi()
                    Art(src = "core:cover/${stored.id}")
                }
            }
        changed(albumId)
        return buildJsonObject { put("art", art.toJson()) }
    }

    // --- songs -----------------------------------------------------------------------------------------------------

    private suspend fun add(params: JsonObject): JsonElement {
        libraryReady()
        val albumId = params.requireStr("id")
        val trackIds = stringList(params, "trackIds")
        val dedupe = params.bool("dedupe") ?: true
        val entity = requireEntity(albumId)
        val songs = songsOf(trackIds)
        val added =
            writeLock.withLock {
                withContext(Dispatchers.IO) {
                    val present = libraryDao.playlistSongMaps(entity.id, Int.MIN_VALUE).mapTo(HashSet()) { it.songId }
                    val seen = HashSet<String>()
                    // With dedupe a song already in the album, or listed twice in the request, is skipped up front:
                    // addSongsToLocalPlaylist would stop at the first duplicate.
                    val wanted = if (dedupe) songs.filter { it.id !in present && seen.add(it.id) } else songs
                    var total = 0
                    for (chunk in wanted.chunked(CHUNK)) {
                        // addSongsToLocalPlaylist(database, playlistId, songs, allowFirstDuplicate): PlaylistAddBatchResult(addedCount, failure)
                        val result = addSongsToLocalPlaylist(libraryDatabase, entity.id, chunk, allowFirstDuplicate = !dedupe)
                        total += result.addedCount
                        val failure = result.failure
                        if (failure != null) {
                            if (failure is PlaylistSongAlreadyPresentException) break
                            throw ApiException("internal", failure.message ?: failure::class.java.simpleName)
                        }
                    }
                    total
                }
            }
        changed(albumId)
        return buildJsonObject {
            put("added", added)
            put("skipped", trackIds.size - added)
        }
    }

    private suspend fun remove(params: JsonObject): JsonElement {
        libraryReady()
        val albumId = params.requireStr("id")
        val entryIds =
            stringList(params, "entryIds").map { it.toIntOrNull() ?: badRequest("entry ids are numbers") }.toSet()
        val entity = requireEntity(albumId)
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val byId = libraryDao.playlistSongMaps(entity.id, Int.MIN_VALUE).associateBy { it.id }
                for (entryId in entryIds) {
                    val mapping = byId[entryId] ?: continue
                    // removeSongFromLocalPlaylist(database, mapping: PlaylistSongMap): Result<Unit>
                    removeSongFromLocalPlaylist(libraryDatabase, mapping).orApi()
                }
            }
        }
        changed(albumId)
        return emptyResult()
    }

    private suspend fun move(params: JsonObject): JsonElement {
        libraryReady()
        val albumId = params.requireStr("id")
        val from = params.int("from") ?: badRequest("missing number param 'from'")
        val to = params.int("to") ?: badRequest("missing number param 'to'")
        val expected = params.long("expectedRevision") ?: badRequest("missing number param 'expectedRevision'")
        val entity = requireEntity(albumId)
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                // PlaylistDao.playlistSongMaps(playlistId, from: Int): entries in window order (position, id).
                val entryIds = libraryDao.playlistSongMaps(entity.id, Int.MIN_VALUE).map { it.id }
                val signature = AlbumRevisionLog.signature(entryIds)
                if (LibraryApi.albumLog.isStale(albumId, expected, LibraryChanges.revision, signature)) {
                    throw ApiException("conflict", "the album changed since revision $expected", retryable = true)
                }
                val (fromEntry, toEntry) = UserAlbumMath.translate(entryIds, from, to) ?: return@withContext
                // moveSongInLocalPlaylist(database, playlistId, fromMappingId, toMappingId): Result<Unit>
                moveSongInLocalPlaylist(libraryDatabase, entity.id, fromEntry, toEntry).orApi()
            }
        }
        changed(albumId)
        return emptyResult()
    }

    // --- pins, list ------------------------------------------------------------------------------------------------

    private suspend fun pin(params: JsonObject): JsonElement {
        libraryReady()
        val albumId = params.requireStr("id")
        val pinned = params.bool("pinned") ?: badRequest("missing boolean param 'pinned'")
        requireEntity(albumId)
        CorePrefs.shared.setPinned(albumId, pinned)
        changed(albumId)
        return emptyResult()
    }

    private suspend fun list(): JsonElement {
        libraryReady()
        val revision = LibraryChanges.revision
        val items =
            withContext(Dispatchers.IO) {
                val dao = libraryDao
                val entities = dao.userAlbums().first()
                val stats = dao.playlistLibraryStats(entities.map { it.id }).associateBy { it.playlistId }
                val prefs = CorePrefs.shared
                prefs.retainPinned(entities.mapTo(HashSet()) { LibraryDto.userAlbumId(it.id) })
                val pins = prefs.pinnedUserAlbums()
                entities.map { entity ->
                    val id = LibraryDto.userAlbumId(entity.id)
                    val card = LibraryDto.userAlbumCard(entity, stats[entity.id]?.songCount ?: 0).toJson().jsonObject
                    JsonObject(card + mapOf("pinned" to JsonPrimitive(id in pins), "revision" to JsonPrimitive(revision)))
                }
            }
        return buildJsonObject { put("items", JsonArray(items)) }
    }

    private const val CHUNK = 100
}

/** Index arithmetic of `userAlbum.move`; pure. */
object UserAlbumMath {
    /**
     * Window indices to entry ids: the entry at [from] moves to the slot of the entry at [to]. Null when nothing moves
     * (same index). An index outside the album is `bad_request`.
     */
    fun translate(
        entryIds: List<Int>,
        from: Int,
        to: Int,
    ): Pair<Int, Int>? {
        if (from !in entryIds.indices || to !in entryIds.indices) badRequest("move indices must be within 0..${entryIds.size - 1}")
        return if (from == to) null else entryIds[from] to entryIds[to]
    }
}
