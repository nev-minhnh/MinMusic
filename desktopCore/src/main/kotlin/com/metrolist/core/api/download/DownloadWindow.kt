package com.metrolist.core.api.download

import com.metrolist.core.api.Track
import com.metrolist.core.api.toJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer

/** A downloaded song with the values the sorts need. */
data class DownloadRow(
    val track: Track,
    val downloadedAt: Long,
    val plays: Long,
)

data class DownloadFilter(
    val text: String? = null,
    val source: String? = null,
    val liked: Boolean? = null,
    val explicit: Boolean? = null,
)

/** The `downloaded` window (api.md 1.8B) over [DownloadRow]s: sort, filter, slice and a content-based revision. Pure. */
object DownloadWindow {
    private const val MAX_SAFE_REVISION = 0x1FFFFFFFFFFFFFL

    fun fold(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .lowercase()

    fun matches(
        row: DownloadRow,
        filter: DownloadFilter,
    ): Boolean {
        val t = row.track
        filter.text?.takeIf { it.isNotBlank() }?.let { raw ->
            val q = fold(raw.trim())
            val hit =
                fold(t.title).contains(q) ||
                    t.artists.any { fold(it.name).contains(q) } ||
                    (t.album?.title?.let { fold(it).contains(q) } == true)
            if (!hit) return false
        }
        filter.source?.let { if (t.source != it) return false }
        filter.liked?.let { if ((t.liked ?: false) != it) return false }
        filter.explicit?.let { if ((t.explicit ?: false) != it) return false }
        return true
    }

    /** Sorts: added (download date), title, artist, album, duration, plays; lastPlayed and custom fall back to added. */
    fun sorted(
        rows: List<DownloadRow>,
        by: String,
        desc: Boolean,
    ): List<DownloadRow> {
        val cmp: Comparator<DownloadRow> =
            when (by) {
                "title" -> compareBy<DownloadRow> { fold(it.track.title) }
                "artist" -> compareBy<DownloadRow> { fold(it.track.artists.firstOrNull()?.name.orEmpty()) }
                "album" -> compareBy<DownloadRow> { fold(it.track.album?.title.orEmpty()) }
                "duration" -> compareBy<DownloadRow> { it.track.durationMs ?: 0L }
                "plays" -> compareBy<DownloadRow> { it.plays }
                else -> compareBy<DownloadRow> { it.downloadedAt }
            }.thenBy { it.track.id }
        val asc = rows.sortedWith(cmp)
        return if (desc) asc.asReversed() else asc
    }

    fun revisionOf(rows: List<DownloadRow>): Long = rows.fold(1L) { acc, r -> acc * 31 + r.track.id.hashCode() + r.downloadedAt } and MAX_SAFE_REVISION

    fun window(
        all: List<DownloadRow>,
        by: String,
        desc: Boolean,
        filter: DownloadFilter,
        offset: Int,
        limit: Int,
    ): JsonObject {
        val revision = revisionOf(all)
        val picked = sorted(all.filter { matches(it, filter) }, by, desc)
        val slice = if (offset >= picked.size) emptyList() else picked.subList(offset, minOf(picked.size, offset + limit))
        return buildJsonObject {
            put("total", picked.size)
            put("revision", revision)
            put("offset", offset)
            put("items", JsonArray(slice.map { it.track.toJson() }))
        }
    }
}

/** `download.queue` items: the track plus `state`, `progress`, `bytes?`, `error?`. */
object QueueItemMapper {
    fun item(
        track: Track,
        state: String,
        progress: Double,
        error: String?,
    ): JsonObject {
        val base = track.copy(download = state).toJson() as JsonObject
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("state", state)
            put("progress", progress.coerceIn(0.0, 1.0))
            if (error != null) put("error", error)
        }
    }
}
