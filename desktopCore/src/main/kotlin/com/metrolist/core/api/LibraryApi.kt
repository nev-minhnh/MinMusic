package com.metrolist.core.api

import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.bool
import com.metrolist.core.server.handler
import com.metrolist.core.server.int
import com.metrolist.core.server.obj
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.core.server.unsupported
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext

/** api.md 3.4, M0 subset: `library.counts` and `library.window` for the song lists (songs, liked, downloaded). */
object LibraryApi {
    private val dao: DatabaseDao get() = GlobalContext.get().get()

    val handlers: Map<String, Handler> =
        mapOf(
            "library.counts" to handler { _, _ -> counts() },
            "library.window" to handler { params, _ -> window(params) },
        )

    suspend fun counts(): JsonObject {
        backendReady()
        return withContext(Dispatchers.IO) {
            val db = dao
            buildJsonObject {
                put("songs", db.songsByCreateDateAsc().first().size)
                put("liked", db.likedSongsCount().first())
                put("downloaded", db.downloadedSongsByCreateDateAsc().first().size)
                put("albums", db.albumsByCreateDateAsc().first().size)
                put("artists", db.artistsByCreateDateAsc().first().size)
                put("playlists", db.playlistsByCreateDateAsc().first().size)
                put("userAlbums", 0)
            }
        }
    }

    /** The songs of a local list in the order of [sort]; the full list is loaded, then sliced by [window]. */
    suspend fun songs(
        list: String,
        sort: JsonObject?,
        filter: JsonObject?,
    ): List<Song> {
        val by = sort?.str("by") ?: "added"
        val desc = sort?.bool("desc") ?: true
        val db = dao
        val base: List<Song> =
            when (list) {
                "songs" -> {
                    when (by) {
                        "title" -> db.songsByNameAsc().first()
                        "plays" -> db.songsByPlayTimeAsc().first()
                        else -> db.songsByCreateDateAsc().first()
                    }
                }

                "liked" -> {
                    when (by) {
                        "title" -> db.likedSongsByNameAsc().first()
                        "plays" -> db.likedSongsByPlayTimeAsc().first()
                        else -> db.likedSongsByCreateDateAsc().first()
                    }
                }

                "downloaded" -> {
                    when (by) {
                        "title" -> db.downloadedSongsByNameAsc().first()
                        "plays" -> db.downloadedSongsByPlayTimeAsc().first()
                        else -> db.downloadedSongsByCreateDateAsc().first()
                    }
                }

                else -> {
                    unsupported("library list '$list' is not available in M0")
                }
            }
        val sorted =
            when (by) {
                "artist" -> base.sortedBy { it.orderedArtists.firstOrNull()?.name?.lowercase().orEmpty() }
                "album" -> base.sortedBy { it.song.albumName?.lowercase().orEmpty() }
                "duration" -> base.sortedBy { it.song.duration }
                else -> base
            }
        val filtered = if (filter == null) sorted else sorted.filter { matches(it, filter) }
        return if (desc) filtered.asReversed() else filtered
    }

    private fun matches(
        song: Song,
        filter: JsonObject,
    ): Boolean {
        filter.str("text")?.takeIf { it.isNotBlank() }?.let { text ->
            val hit =
                song.song.title.contains(text, ignoreCase = true) ||
                    song.orderedArtists.any { it.name.contains(text, ignoreCase = true) } ||
                    song.song.albumName?.contains(text, ignoreCase = true) == true
            if (!hit) return false
        }
        filter.str("source")?.let { if (song.song.source != it) return false }
        filter.bool("downloaded")?.let { if (song.song.isDownloaded != it) return false }
        filter.bool("liked")?.let { if (song.song.liked != it) return false }
        filter.bool("explicit")?.let { if (song.song.explicit != it) return false }
        return true
    }

    private fun revisionOf(songs: List<Song>): Long = songs.fold(1L) { acc, song -> acc * 31 + song.song.id.hashCode() } and MAX_SAFE_REVISION

    suspend fun window(params: JsonObject): JsonElement {
        backendReady()
        val list = params.requireStr("list")
        val offset = (params.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (params.int("limit") ?: badRequest("missing number param 'limit'")).coerceIn(1, MAX_WINDOW)
        val all = withContext(Dispatchers.IO) { songs(list, params.obj("sort"), params.obj("filter")) }
        val slice = if (offset >= all.size) emptyList() else all.subList(offset, minOf(all.size, offset + limit))
        return buildJsonObject {
            put("total", all.size)
            put("revision", revisionOf(all))
            put("offset", offset)
            put("items", JsonArray(slice.map { it.toTrack().toJson() }))
        }
    }

    private const val MAX_WINDOW = 200
    private const val MAX_SAFE_REVISION = 0x1FFFFFFFFFFFFFL
}
