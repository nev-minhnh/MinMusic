package com.metrolist.core.api.library

import com.metrolist.core.server.badRequest
import com.metrolist.core.server.bool
import com.metrolist.core.server.str
import com.metrolist.shared.db.entities.Song
import kotlinx.serialization.json.JsonObject

/** The lists `library.window` serves (api.md section 2, `LocalList`). */
enum class ListKind(
    val wire: String,
) {
    SONGS("songs"),
    LIKED("liked"),
    DOWNLOADED("downloaded"),
    CACHED("cached"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    PLAYLISTS("playlists"),
    HISTORY("history"),
    USER_ALBUM("userAlbum"),
    ;

    /** True for the lists whose rows are songs (`Track`s). */
    val isSongList: Boolean get() = this == SONGS || this == LIKED || this == DOWNLOADED || this == CACHED || this == USER_ALBUM
}

/** A parsed `LocalList`; [albumId] is the full `lp:<id>` for [ListKind.USER_ALBUM]. */
data class ListRef(
    val kind: ListKind,
    val albumId: String? = null,
) {
    val wire: String get() = if (kind == ListKind.USER_ALBUM) "userAlbum:$albumId" else kind.wire

    companion object {
        fun parse(list: String): ListRef {
            if (list.startsWith("userAlbum:")) {
                val id = list.removePrefix("userAlbum:")
                if (id.isBlank()) badRequest("empty user album id in list '$list'")
                return ListRef(ListKind.USER_ALBUM, if (id.startsWith("lp:")) id else "lp:$id")
            }
            val kind = ListKind.entries.firstOrNull { it != ListKind.USER_ALBUM && it.wire == list } ?: badRequest("unknown list '$list'")
            return ListRef(kind)
        }
    }
}

data class SortSpec(
    val by: String,
    val desc: Boolean,
) {
    val key: String get() = "$by:$desc"

    companion object {
        val DEFAULT = SortSpec("added", true)
        private val SONG_SORTS = setOf("added", "title", "artist", "album", "duration", "plays", "lastPlayed")

        /** Missing sort means newest first; `desc` defaults to true like the UI's default. */
        fun parse(raw: JsonObject?): SortSpec = raw?.let { SortSpec(it.str("by") ?: DEFAULT.by, it.bool("desc") ?: DEFAULT.desc) } ?: DEFAULT

        /** Sorts a list kind supports; anything else is `bad_request` (api.md 3.4). */
        fun allowed(kind: ListKind): Set<String> =
            when (kind) {
                ListKind.SONGS, ListKind.LIKED, ListKind.DOWNLOADED, ListKind.CACHED -> SONG_SORTS
                ListKind.USER_ALBUM -> SONG_SORTS + "custom"
                ListKind.ALBUMS -> setOf("added", "title", "artist", "duration")
                ListKind.ARTISTS, ListKind.PLAYLISTS -> setOf("added", "title")
                ListKind.HISTORY -> setOf("added", "title", "artist", "plays")
            }
    }

    fun validate(kind: ListKind) {
        if (by !in allowed(kind)) badRequest("list '${kind.wire}' cannot be sorted by '$by'")
    }
}

/** `FilterSpec` of api.md section 2; [text] is already folded by [VnCollation.fold]. */
data class FilterSpec(
    val text: String? = null,
    val source: String? = null,
    val downloaded: Boolean? = null,
    val liked: Boolean? = null,
    val explicit: Boolean? = null,
) {
    val isEmpty: Boolean get() = text == null && source == null && downloaded == null && liked == null && explicit == null
    val key: String get() = "$text|$source|$downloaded|$liked|$explicit"

    companion object {
        val NONE = FilterSpec()

        fun parse(raw: JsonObject?): FilterSpec =
            if (raw == null) {
                NONE
            } else {
                FilterSpec(
                    text = raw.str("text")?.takeIf { it.isNotBlank() }?.let { VnCollation.fold(it) }?.trim(),
                    source = raw.str("source"),
                    downloaded = raw.bool("downloaded"),
                    liked = raw.bool("liked"),
                    explicit = raw.bool("explicit"),
                )
            }
    }
}

/** Filtering and ordering of song rows. Pure over [Song]; [T] is the row type (a [Song] or a playlist entry). */
object SongRows {
    /** Folded `title artist artist album` of a song, matched with `contains` against [FilterSpec.text]. */
    fun haystack(song: Song): String =
        VnCollation.fold(
            buildString {
                append(song.song.customTitle ?: song.song.title)
                append('\n')
                song.orderedArtists.forEach {
                    append(it.name)
                    append('\n')
                }
                song.song.albumName?.let(::append)
            },
        )

    fun matches(
        song: Song,
        filter: FilterSpec,
    ): Boolean {
        if (filter.isEmpty) return true
        filter.text?.let { if (!haystack(song).contains(it)) return false }
        filter.source?.let { if (song.song.source != it) return false }
        filter.downloaded?.let { if (song.song.isDownloaded != it) return false }
        filter.liked?.let { if (song.song.liked != it) return false }
        filter.explicit?.let { if (song.song.explicit != it) return false }
        return true
    }

    fun <T> filter(
        rows: List<T>,
        filter: FilterSpec,
        song: (T) -> Song,
    ): List<T> = if (filter.isEmpty) rows else rows.filter { matches(song(it), filter) }

    /**
     * Rows ordered by [spec]. [rows] must come in "added" order, oldest first (the order the DAO lists use): `added`
     * keeps it, `desc` reverses the result, equal keys keep their relative order. `custom` keeps the list's own order
     * (a user album's position). [lastPlayed] maps a song id to its last play (0 when never played);
     * [addedOrder] overrides the "added" order for rows that carry their own (a user album's entry id).
     */
    fun <T> sort(
        rows: List<T>,
        spec: SortSpec,
        song: (T) -> Song,
        lastPlayed: (String) -> Long = { 0L },
        addedOrder: ((T) -> Long)? = null,
    ): List<T> {
        val ascending: List<T> =
            when (spec.by) {
                "added" -> if (addedOrder == null) rows else sortedByKey(rows) { addedOrder(it) }
                "custom" -> rows
                "title" -> sortedByKey(rows) { VnCollation.key(song(it).song.customTitle ?: song(it).song.title) }
                "artist" -> sortedByKey(rows) { VnCollation.key(song(it).orderedArtists.firstOrNull()?.name.orEmpty()) }
                "album" -> sortedByKey(rows) { VnCollation.key(song(it).song.albumName.orEmpty()) }
                "duration" -> sortedByKey(rows) { song(it).song.duration.toLong() }
                "plays" -> sortedByKey(rows) { song(it).song.totalPlayTime }
                "lastPlayed" -> sortedByKey(rows) { lastPlayed(song(it).song.id) }
                else -> badRequest("unsupported sort '${spec.by}'")
            }
        return if (spec.desc) ascending.asReversed() else ascending
    }

    /** Stable sort by a precomputed key. */
    fun <T, K : Comparable<K>> sortedByKey(
        rows: List<T>,
        key: (T) -> K,
    ): List<T> {
        val keys = rows.map(key)
        return rows.indices.sortedWith { a, b -> keys[a].compareTo(keys[b]) }.map { rows[it] }
    }
}
