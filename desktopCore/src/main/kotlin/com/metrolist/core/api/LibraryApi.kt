package com.metrolist.core.api

import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreLog
import com.metrolist.core.api.library.AlbumRevisionLog
import com.metrolist.core.api.library.FilterSpec
import com.metrolist.core.api.library.LibraryChanges
import com.metrolist.core.api.library.LibraryCounts
import com.metrolist.core.api.library.LibraryLists
import com.metrolist.core.api.library.LikeMirror
import com.metrolist.core.api.library.ListKind
import com.metrolist.core.api.library.ListRef
import com.metrolist.core.api.library.SortSpec
import com.metrolist.core.api.library.libraryDao
import com.metrolist.core.api.library.libraryDatabase
import com.metrolist.core.api.library.libraryReady
import com.metrolist.core.server.Handler
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.int
import com.metrolist.core.server.notFound
import com.metrolist.core.server.obj
import com.metrolist.core.server.requireStr
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.viewmodels.LibraryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * api.md 3.4: `library.counts`, `library.window`, `library.indexOf`, `library.sync`. Windows are served from a sorted id
 * list cached per `(list, sort, filter, revision)` (see [LibraryLists]); the revision is [LibraryChanges.revision].
 */
object LibraryApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "library.counts" to handler { _, _ -> counts() },
            "library.window" to handler { params, _ -> window(params) },
            "library.indexOf" to handler { params, _ -> indexOf(params) },
            "library.sync" to handler { _, _ -> sync() },
        )

    /** Per-album record of what each window saw, for `userAlbum.move` conflict checks. */
    internal val albumLog = AlbumRevisionLog()

    const val MAX_WINDOW = 200

    suspend fun counts(): JsonObject {
        libraryReady()
        return countsJson(LibraryLists.counts(LibraryChanges.revision))
    }

    private fun countsJson(counts: LibraryCounts): JsonObject =
        buildJsonObject {
            put("songs", counts.songs)
            put("liked", counts.liked)
            put("downloaded", counts.downloaded)
            put("albums", counts.albums)
            put("artists", counts.artists)
            put("playlists", counts.playlists)
            put("userAlbums", counts.userAlbums)
        }

    /** The `library` topic's `library.snapshot` payload. */
    suspend fun snapshot(): JsonObject {
        libraryReady()
        val revision = LibraryChanges.revision
        return buildJsonObject {
            put("counts", countsJson(LibraryLists.counts(revision)))
            put("revision", revision)
        }
    }

    /**
     * The songs of a local song list, in the order of [sort] and narrowed by [filter]; M0's `player.play {kind:"library"}`
     * and `queue.addContext` use it. Lists that are not song lists are `bad_request`.
     */
    suspend fun songs(
        list: String,
        sort: JsonObject?,
        filter: JsonObject?,
    ): List<Song> {
        libraryReady()
        val ref = ListRef.parse(list)
        if (!ref.kind.isSongList) badRequest("'$list' is not a list of songs")
        val spec = SortSpec.parse(sort)
        spec.validate(ref.kind)
        val ordered = LibraryLists.get(ref, spec, FilterSpec.parse(filter), LibraryChanges.revision)
        return withContext(Dispatchers.IO) {
            val stored = libraryDao.getSongsByIds(ordered.ids.distinct()).associateBy { it.song.id }
            ordered.ids.mapNotNull { stored[it] }
        }
    }

    /** `library.window`, and `history.window` with `only = HISTORY` (that list also answers `groups`). */
    suspend fun window(
        params: JsonObject,
        only: ListKind? = null,
    ): JsonElement {
        libraryReady()
        val ref = ListRef.parse(params.requireStr("list"))
        if (only != null && ref.kind != only) badRequest("this method serves the '${only.wire}' list only")
        val offset = params.int("offset") ?: 0
        val limit = params.int("limit") ?: badRequest("missing number param 'limit'")
        if (offset < 0) badRequest("offset must be >= 0")
        if (limit !in 1..MAX_WINDOW) badRequest("limit must be 1..$MAX_WINDOW")
        val sort = SortSpec.parse(params.obj("sort"))
        sort.validate(ref.kind)
        val filter = FilterSpec.parse(params.obj("filter"))
        // The revision is read before the list is loaded: a write that lands meanwhile makes the next call rebuild.
        val revision = LibraryChanges.revision
        val list = LibraryLists.get(ref, sort, filter, revision)
        if (ref.kind == ListKind.USER_ALBUM) albumLog.record(ref.albumId!!, revision, list.signature)
        val from = minOf(offset, list.size)
        val to = minOf(list.size, from + limit)
        val items = if (from >= to) emptyList() else list.slice(from, to)
        return buildJsonObject {
            put("total", list.size)
            put("revision", revision)
            put("offset", offset)
            put("items", JsonArray(items))
            if (ref.kind == ListKind.HISTORY) {
                put(
                    "groups",
                    JsonArray(list.groups.map { group -> buildJsonObject { put("label", group.label); put("offset", group.offset) } }),
                )
            }
        }
    }

    private suspend fun indexOf(params: JsonObject): JsonElement {
        libraryReady()
        val ref = ListRef.parse(params.requireStr("list"))
        val sort = SortSpec.parse(params.obj("sort"))
        sort.validate(ref.kind)
        val list = LibraryLists.get(ref, sort, FilterSpec.parse(params.obj("filter")), LibraryChanges.revision)
        val index = list.ids.indexOf(params.requireStr("id"))
        if (index < 0) notFound("not in list '${ref.wire}'")
        return buildJsonObject { put("index", index) }
    }

    private val libraryViewModel: LibraryViewModel by lazy { LibraryViewModel(CoreBackend.scope, libraryDatabase) }

    /** Pulls the YouTube library and likes when signed in (progress arrives as `library.changed`); otherwise nothing to do. */
    private suspend fun sync(): JsonElement {
        libraryReady()
        if (!AuthState.isSignedIn) return emptyResult()
        // refresh(force: Boolean = true): starts the pull in the view model's scope.
        withContext(Dispatchers.IO) { libraryViewModel.refresh(force = true) }
        CoreBackend.scope.launch(Dispatchers.IO) {
            try {
                // refreshLikesFromRemote(force: Boolean = true, ...): suspend; the YouTube likes land in SongLikeState.
                SongLikeState.refreshLikesFromRemote(true)
                LikeMirror.sync()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CoreLog.error("library.sync: likes refresh failed", error)
            } finally {
                LibraryChanges.bump(LibraryChanges.ALL_LISTS)
            }
        }
        return emptyResult()
    }
}
