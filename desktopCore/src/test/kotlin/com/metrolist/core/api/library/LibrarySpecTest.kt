package com.metrolist.core.api.library

import com.metrolist.core.server.ApiException
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySpecTest {
    private fun song(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String? = null,
        seconds: Int = 200,
        plays: Long = 0,
        liked: Boolean = false,
        downloaded: Boolean = false,
        explicit: Boolean = false,
    ): Song =
        Song(
            song =
                SongEntity(
                    id = id,
                    title = title,
                    duration = seconds,
                    albumName = album,
                    liked = liked,
                    totalPlayTime = plays,
                    isDownloaded = downloaded,
                    explicit = explicit,
                    inLibrary = LibraryDto.utc(1_700_000_000_000),
                ),
            artists = listOf(ArtistEntity(id = "UC$artist", name = artist)),
        )

    private fun ids(rows: List<Song>) = rows.map { it.song.id }

    // --- list and spec parsing -------------------------------------------------------------------------------------

    @Test
    fun parsesEveryListKind() {
        for (name in listOf("songs", "liked", "downloaded", "cached", "albums", "artists", "playlists", "history")) {
            assertEquals(name, ListRef.parse(name).wire)
        }
        val album = ListRef.parse("userAlbum:lp:abc123")
        assertEquals(ListKind.USER_ALBUM, album.kind)
        assertEquals("lp:abc123", album.albumId)
        assertEquals("userAlbum:lp:abc123", album.wire)
        assertEquals("lp:abc123", ListRef.parse("userAlbum:abc123").albumId)
    }

    @Test
    fun unknownListIsBadRequest() {
        assertEquals("bad_request", assertFailsWith<ApiException> { ListRef.parse("nope") }.code)
        assertEquals("bad_request", assertFailsWith<ApiException> { ListRef.parse("userAlbum:") }.code)
    }

    @Test
    fun sortSpecDefaultsAndValidation() {
        assertEquals(SortSpec("added", true), SortSpec.parse(null))
        assertEquals(SortSpec("title", true), SortSpec.parse(buildJsonObject { put("by", "title") }))
        SortSpec("custom", false).validate(ListKind.USER_ALBUM)
        assertEquals("bad_request", assertFailsWith<ApiException> { SortSpec("custom", false).validate(ListKind.SONGS) }.code)
        assertEquals("bad_request", assertFailsWith<ApiException> { SortSpec("plays", false).validate(ListKind.ALBUMS) }.code)
        assertEquals("bad_request", assertFailsWith<ApiException> { SortSpec("album", false).validate(ListKind.HISTORY) }.code)
        for (kind in ListKind.entries) SortSpec("added", true).validate(kind)
    }

    @Test
    fun filterSpecFoldsTheText() {
        val spec = FilterSpec.parse(buildJsonObject { put("text", "  Mưa  "); put("source", "zing"); put("liked", true) })
        assertEquals("mua", spec.text)
        assertEquals("zing", spec.source)
        assertEquals(true, spec.liked)
        assertTrue(FilterSpec.parse(null).isEmpty)
        assertTrue(FilterSpec.parse(buildJsonObject { put("text", " ") }).isEmpty)
    }

    // --- filtering --------------------------------------------------------------------------------------------------

    @Test
    fun textFilterIgnoresCaseAndDiacriticsAndSearchesArtistAndAlbum() {
        val rows =
            listOf(
                song("a", "Nhớ Nước Mắt Lặng Lẽ Mưa"),
                song("b", "Other", artist = "Sơn Tùng"),
                song("c", "Third", album = "Biển Nhà"),
                song("d", "None"),
            )

        fun hits(text: String) = ids(SongRows.filter(rows, FilterSpec.parse(buildJsonObject { put("text", text) })) { it })
        assertEquals(listOf("a"), hits("mua"))
        assertEquals(listOf("a"), hits("MƯA"))
        assertEquals(listOf("b"), hits("son tung"))
        assertEquals(listOf("c"), hits("bien nha"))
        assertEquals(emptyList(), hits("zzz"))
    }

    @Test
    fun flagFilters() {
        val rows =
            listOf(
                song("liked", "x", liked = true),
                song("dl", "y", downloaded = true),
                song("explicit", "z", explicit = true),
                song("plain", "w"),
            )

        fun by(filter: FilterSpec) = ids(SongRows.filter(rows, filter) { it })
        assertEquals(listOf("liked"), by(FilterSpec(liked = true)))
        assertEquals(listOf("dl"), by(FilterSpec(downloaded = true)))
        assertEquals(listOf("explicit"), by(FilterSpec(explicit = true)))
        assertEquals(listOf("dl", "explicit", "plain"), by(FilterSpec(liked = false)))
        assertEquals(4, by(FilterSpec.NONE).size)
        assertEquals(4, by(FilterSpec(source = "yt")).size)
        assertEquals(0, by(FilterSpec(source = "zing")).size)
    }

    // --- sorting ----------------------------------------------------------------------------------------------------

    @Test
    fun sortMatrixAscendingAndDescending() {
        val rows =
            listOf(
                song("1", "b", artist = "Z", album = "m", seconds = 300, plays = 5),
                song("2", "a", artist = "Y", album = "k", seconds = 100, plays = 9),
                song("3", "c", artist = "X", album = "l", seconds = 200, plays = 1),
            )
        val lastPlayed = mapOf("1" to 30L, "2" to 10L, "3" to 20L)

        fun order(
            by: String,
            desc: Boolean,
        ) = ids(SongRows.sort(rows, SortSpec(by, desc), { it }, { id -> lastPlayed[id] ?: 0L }))
        assertEquals(listOf("1", "2", "3"), order("added", false)) // the input order is the added order, oldest first
        assertEquals(listOf("3", "2", "1"), order("added", true))
        assertEquals(listOf("2", "1", "3"), order("title", false))
        assertEquals(listOf("3", "1", "2"), order("title", true))
        assertEquals(listOf("3", "2", "1"), order("artist", false))
        assertEquals(listOf("2", "3", "1"), order("album", false))
        assertEquals(listOf("2", "3", "1"), order("duration", false))
        assertEquals(listOf("3", "1", "2"), order("plays", false))
        assertEquals(listOf("2", "3", "1"), order("lastPlayed", false))
        assertEquals(listOf("1", "3", "2"), order("lastPlayed", true))
        assertEquals(listOf("1", "2", "3"), order("custom", false))
    }

    @Test
    fun equalKeysKeepTheirOrderAndAddedOrderOverrideWorks() {
        val rows = listOf(song("1", "same"), song("2", "same"), song("3", "same"))
        assertEquals(listOf("1", "2", "3"), ids(SongRows.sort(rows, SortSpec("title", false), { it })))
        val byEntry = mapOf("1" to 30L, "2" to 10L, "3" to 20L)
        val sorted = SongRows.sort(rows, SortSpec("added", false), { it }, addedOrder = { byEntry.getValue(it.song.id) })
        assertEquals(listOf("2", "3", "1"), ids(sorted))
    }

    @Test
    fun titleSortUsesVietnameseOrder() {
        val rows = listOf(song("1", "Đêm"), song("2", "Dep"), song("3", "Em"), song("4", "Âm"), song("5", "An"))
        val titles = SongRows.sort(rows, SortSpec("title", false), { it }).map { it.song.title }
        assertEquals(listOf("An", "Âm", "Dep", "Đêm", "Em"), titles)
    }

    @Test
    fun unknownSortIsBadRequest() {
        val error = assertFailsWith<ApiException> { SongRows.sort(listOf(song("1", "a")), SortSpec("nope", false), { it }) }
        assertEquals("bad_request", error.code)
    }

    @Test
    fun windowSlicingOfASortedList() {
        val rows = (1..250).map { song("id$it", "t%03d".format(it)) }
        val sorted = SongRows.sort(rows, SortSpec("title", true), { it })
        val window = sorted.subList(200, minOf(sorted.size, 400))
        assertEquals(50, window.size)
        assertEquals("t050", window.first().song.title)
        assertFalse(sorted.isEmpty())
    }
}
