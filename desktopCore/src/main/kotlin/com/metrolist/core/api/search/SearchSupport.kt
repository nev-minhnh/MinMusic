package com.metrolist.core.api.search

import com.metrolist.core.api.jsonOf
import com.metrolist.core.api.merged
import com.metrolist.core.api.toItem
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.source.SourceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Cursor of a non-YouTube search: `"<source key>:<page>"`, for example `"zing:3"`. YouTube cursors are passed through unchanged. */
object SearchCursor {
    private val sources = listOf(SourceId.SC, SourceId.ZING, SourceId.NCT)

    fun encode(
        source: SourceId,
        page: Int,
    ): String = "${source.key}:$page"

    /** The source and zero-based page of a source cursor, or null for anything else (null, a YouTube token, a bad page). */
    fun parse(cursor: String?): Pair<SourceId, Int>? {
        if (cursor == null) return null
        val separator = cursor.indexOf(':')
        if (separator <= 0) return null
        val source = sources.firstOrNull { it.key == cursor.substring(0, separator) } ?: return null
        val page = cursor.substring(separator + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return source to page
    }
}

/** Pure mapping helpers of `search.run`. */
object SearchMapper {
    /** api.md `type` to the YouTube filter; null for `all` and unknown values. */
    fun filterOf(type: String): SearchFilter? =
        when (type) {
            "track" -> SearchFilter.SONG
            "album" -> SearchFilter.ALBUM
            "artist" -> SearchFilter.ARTIST
            "playlist" -> SearchFilter.FEATURED_PLAYLIST
            else -> null
        }

    /** The api.md section type of an item; null for kinds with no wire shape. */
    fun typeOf(item: YTItem): String? =
        when (item) {
            is SongItem -> "track"
            is AlbumItem -> "album"
            is ArtistItem -> "artist"
            is PlaylistItem -> "playlist"
            else -> null
        }

    fun titleOf(type: String): String =
        when (type) {
            "track" -> "Songs"
            "album" -> "Albums"
            "artist" -> "Artists"
            "playlist" -> "Playlists"
            else -> type
        }

    fun sourceLabel(source: SourceId): String =
        when (source) {
            SourceId.ZING -> "Zing MP3"
            SourceId.NCT -> "NhacCuaTui"
            SourceId.SC -> "SoundCloud"
            SourceId.YT -> "YouTube Music"
            SourceId.LOCAL -> "Local"
        }

    /** One `{type, title, items, more?}` section; null when no item can be mapped. */
    fun section(
        type: String,
        title: String,
        items: List<YTItem>,
        more: JsonElement? = null,
    ): JsonObject? {
        val mapped = items.mapNotNull { it.toItem() }
        if (mapped.isEmpty()) return null
        return jsonOf("type" to JsonPrimitive(type), "title" to JsonPrimitive(title), "items" to JsonArray(mapped), "more" to more)
    }

    /** A section of a YouTube summary; its type comes from the first mappable item. */
    fun summarySection(summary: SearchSummary): JsonObject? {
        val type = summary.items.firstNotNullOfOrNull { typeOf(it) } ?: return null
        return section(type, summary.title, summary.items.filter { typeOf(it) == type })
    }

    /** `top` is the first item of the top-result summary with `sources`; the other summaries become sections. */
    fun summaryResult(summaries: List<SearchSummary>): Pair<JsonObject?, List<JsonObject>> {
        val top =
            summaries
                .firstOrNull { it.isTopResult }
                ?.items
                ?.firstNotNullOfOrNull { item -> item.toItem() }
                ?.merged(jsonOf("sources" to JsonArray(listOf(JsonPrimitive("yt")))))
        val sections = summaries.filterNot { it.isTopResult }.mapNotNull { summarySection(it) }
        return top to sections
    }

    /** `{route: "search", params: {q, source, type}}` for the "more" link of a source section. */
    fun searchNav(
        q: String,
        source: SourceId,
        type: String,
    ): JsonObject =
        jsonOf(
            "route" to JsonPrimitive("search"),
            "params" to jsonOf("q" to JsonPrimitive(q), "source" to JsonPrimitive(source.key), "type" to JsonPrimitive(type)),
        )

    const val MAX_HISTORY = 50

    /** [query] moved to the front of [history] (no duplicates, case-sensitive), capped at [MAX_HISTORY]; blank queries change nothing. */
    fun addToHistory(
        history: List<String>,
        query: String,
    ): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return history
        return (listOf(q) + history.filterNot { it == q }).take(MAX_HISTORY)
    }

    /** Removes [query] from [history], or everything when [query] is null. */
    fun removeFromHistory(
        history: List<String>,
        query: String?,
    ): List<String> = if (query == null) emptyList() else history.filterNot { it == query }
}
