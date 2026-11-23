package com.metrolist.core.api.library

import com.metrolist.core.api.AlbumCard
import com.metrolist.core.api.AlbumRef
import com.metrolist.core.api.Art
import com.metrolist.core.api.ArtistCard
import com.metrolist.core.api.ArtistRef
import com.metrolist.core.api.LibraryApi
import com.metrolist.core.api.Track
import com.metrolist.core.api.toJson
import com.metrolist.core.api.toTrack
import com.metrolist.core.server.Handler
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.desktop.history.HistoryRepository
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.shared.db.StoredHistoryRow
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.history
import com.metrolist.shared.db.visibleHistory
import com.metrolist.shared.source.sourceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** api.md 3.4: `history.window` (also reachable as `library.window` with list `history`), `history.remove`, `history.clear`, `stats.get`. */
object HistoryApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "history.window" to handler { params, _ -> LibraryApi.window(params, only = ListKind.HISTORY) },
            "history.remove" to handler { params, _ -> remove(params) },
            "history.clear" to handler { _, _ -> clear() },
            "stats.get" to handler { params, _ -> stats(params.requireStr("period")) },
        )

    private suspend fun remove(params: JsonObject): JsonElement {
        libraryReady()
        val ids =
            (params["eventIds"] as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.content?.toLongOrNull() ?: badRequest("eventIds must be numeric strings") }
                ?.toSet()
                ?: badRequest("missing 'eventIds'")
        delete(ids)
        return emptyResult()
    }

    private suspend fun clear(): JsonElement {
        libraryReady()
        // Every play, not only the latest one per song that HistoryRepository.getVisibleHistoryIds("") lists.
        val ids = withContext(Dispatchers.IO) { libraryDatabase.history().mapTo(HashSet()) { it.id } }
        delete(ids)
        return emptyResult()
    }

    private suspend fun delete(ids: Set<Long>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            // deleteHistoryEntries(ids: Set<Long>, deleteRemote: Boolean); the remote call needs a signed-in session and tokens.
            val remote = AuthState.isSignedIn && HistoryRepository.hasHistoryRemoteTokens(ids)
            HistoryRepository.deleteHistoryEntries(ids, deleteRemote = remote)
        }
        LibraryChanges.bump(listOf("history", "songs"))
    }

    // --- stats.get -----------------------------------------------------------------------------------------------

    private suspend fun stats(period: String): JsonElement {
        libraryReady()
        val now = System.currentTimeMillis()
        val since = HistoryGroups.periodStart(period, now) ?: badRequest("period must be week, month, year or all")
        val snapshot = withContext(Dispatchers.IO) { HistoryRepository.getStatisticsSnapshot(since, Long.MAX_VALUE) }
        val items = snapshot.songItems.associateBy { it.id }
        val topTracks =
            snapshot.songs.take(TOP).map { group ->
                items[group.songId]?.toTrack()
                    ?: Track(
                        id = group.songId,
                        title = group.title,
                        artists = group.artists.split(", ").filter { it.isNotBlank() }.map { ArtistRef(name = it) },
                        album = group.album?.let { AlbumRef(group.albumId, it) },
                        durationMs = group.duration?.takeIf { it > 0 }?.times(1000),
                        art = group.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { Art(src = it) },
                        source = sourceOf(group.songId).key,
                    )
            }
        val topArtists =
            snapshot.artists.take(TOP).map {
                val id = it.id ?: it.name
                ArtistCard(
                    id = id,
                    name = it.name,
                    art = it.thumbnailUrl?.takeIf { url -> url.isNotBlank() }?.let { url -> Art(src = url) },
                    source = sourceOf(id).key,
                )
            }
        val topAlbums =
            snapshot.albums.take(TOP).map {
                val id = it.id ?: it.name
                AlbumCard(
                    id = id,
                    title = it.name,
                    artists = it.artists.split(", ").filter { name -> name.isNotBlank() }.map { name -> ArtistRef(name = name) },
                    art = it.thumbnailUrl?.takeIf { url -> url.isNotBlank() }?.let { url -> Art(src = url) },
                    source = sourceOf(id).key,
                    kind = "album",
                )
            }
        return buildJsonObject {
            put("minutes", snapshot.totals.playTimeMs / 60_000)
            put("topTracks", JsonArray(topTracks.map { it.toJson() }))
            put("topArtists", JsonArray(topArtists.map { it.toJson() }))
            put("topAlbums", JsonArray(topAlbums.map { it.toJson() }))
        }
    }

    private const val TOP = 10
}

/** The play log behind the `history` list: one row per play event, newest first (not one per song as in the v1 screen). */
internal object HistoryData {
    private val cache = RevisionCache<List<StoredHistoryRow>>(2)

    suspend fun events(revision: Long): List<StoredHistoryRow> =
        cache.getOrBuild("all", revision) {
            withContext(Dispatchers.IO) {
                HistoryRepository.initialize()
                libraryDatabase
                    .history()
                    .visibleHistory()
                    .sortedWith(compareByDescending<StoredHistoryRow> { it.playedAt }.thenByDescending { it.id })
            }
        }

    suspend fun list(
        sort: SortSpec,
        filter: FilterSpec,
        revision: Long,
    ): WindowList {
        val all = events(revision)
        val rows =
            all.filter { row ->
                (filter.text == null || VnCollation.fold("${row.title}\n${row.artists}\n${row.album.orEmpty()}").contains(filter.text)) &&
                    (filter.source == null || sourceOf(row.songId).key == filter.source)
            }
        fun <K : Comparable<K>> byKey(key: (StoredHistoryRow) -> K): List<StoredHistoryRow> =
            SongRows.sortedByKey(rows, key).let { if (sort.desc) it.asReversed() else it }
        val ordered: List<StoredHistoryRow> =
            when (sort.by) {
                "added" -> if (sort.desc) rows else rows.asReversed() // rows are newest first
                "title" -> byKey { VnCollation.key(it.title) }
                "artist" -> byKey { VnCollation.key(it.artists) }
                "plays" -> byKey { it.playTimeMs ?: 0L }
                else -> badRequest("history cannot be sorted by '${sort.by}'")
            }
        val groups = if (sort.by == "added") HistoryGroups.compute(ordered.map { it.playedAt }, System.currentTimeMillis(), ZoneId.systemDefault()) else emptyList()
        return WindowList(ids = ordered.map { it.songId }, groups = groups) { from, to ->
            val page = ordered.subList(from, to)
            val songs = libraryDao.getSongsByIds(page.map { it.songId }.distinct()).associateBy { it.song.id }
            page.map { track(it, songs[it.songId]) }
        }
    }

    /** `Track & {playedAt, eventId}`: the stored song when the library has it, else what the play itself recorded. */
    fun track(
        row: StoredHistoryRow,
        song: Song?,
    ): JsonObject {
        val base =
            song?.toTrack()
                ?: Track(
                    id = row.songId,
                    title = row.title,
                    artists = (row.artistNames ?: row.artists.split(", ")).filter { it.isNotBlank() }.map { ArtistRef(name = it) },
                    album = row.album?.let { AlbumRef(row.albumId, it) },
                    durationMs = row.duration?.takeIf { it > 0 }?.times(1000),
                    art = row.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { Art(src = it) },
                    source = sourceOf(row.songId).key,
                )
        return JsonObject(
            base.toJson().jsonObject + mapOf("playedAt" to JsonPrimitive(row.playedAt), "eventId" to JsonPrimitive(row.id.toString())),
        )
    }
}

/** Day groups of the history list (Hôm nay / Hôm qua / Tuần này / Tháng này / Cũ hơn) and the `stats.get` periods. Pure. */
object HistoryGroups {
    data class Group(
        val label: String,
        val offset: Int,
    )

    const val TODAY = "w2_hist_today"
    const val YESTERDAY = "w2_hist_yesterday"
    const val WEEK = "w2_hist_week"
    const val MONTH = "w2_hist_month"
    const val OLDER = "w2_hist_older"

    /**
     * The group of a play. Days are calendar days in [zone]: today, yesterday, the 2 to 6 days before that ("week"),
     * the rest of the current calendar month, and everything older. A play from the future counts as today.
     */
    fun labelOf(
        playedAtMs: Long,
        nowMs: Long,
        zone: ZoneId,
    ): String {
        val day: LocalDate = Instant.ofEpochMilli(playedAtMs).atZone(zone).toLocalDate()
        val today: LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when {
            !day.isBefore(today) -> TODAY
            day == today.minusDays(1) -> YESTERDAY
            day.isAfter(today.minusDays(7)) -> WEEK
            day.year == today.year && day.month == today.month -> MONTH
            else -> OLDER
        }
    }

    /** Runs of equal labels over [playedAt], in the order given (newest first or oldest first); offsets index into [playedAt]. */
    fun compute(
        playedAt: List<Long>,
        nowMs: Long,
        zone: ZoneId,
    ): List<Group> {
        val groups = ArrayList<Group>()
        var previous: String? = null
        playedAt.forEachIndexed { index, ms ->
            val label = labelOf(ms, nowMs, zone)
            if (label != previous) groups += Group(label, index)
            previous = label
        }
        return groups
    }

    /** Start (epoch ms) of a `stats.get` period; null for an unknown one. */
    fun periodStart(
        period: String,
        nowMs: Long,
    ): Long? {
        val day = 24L * 60 * 60 * 1000
        return when (period) {
            "week" -> nowMs - 7 * day
            "month" -> nowMs - 30 * day
            "year" -> nowMs - 365 * day
            "all" -> 0L
            else -> null
        }
    }
}
