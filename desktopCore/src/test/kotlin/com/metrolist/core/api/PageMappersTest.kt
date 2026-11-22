package com.metrolist.core.api

import com.metrolist.core.api.browse.BrowseApi
import com.metrolist.core.api.browse.ChartMapper
import com.metrolist.core.api.pages.PageMappers
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.viewmodels.LastFmArtistData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageMappersTest {
    private val artist = ArtistTiny("UC1", "Artist One")

    private fun song(
        id: String,
        duration: Long? = 100,
        thumbnail: String? = null,
    ) = SongItem(id = id, title = "T $id", thumbnail = thumbnail, artists = listOf(artist), album = null, duration = duration)

    private fun album(
        id: String = "MPREb_1",
        type: String? = null,
        year: String? = "2020",
    ) = AlbumItem(id = id, title = "Album", thumbnail = "https://img/a.jpg", artists = listOf(artist), year = year, type = type)

    @Test
    fun cardsEqualTheItemMapperMinusTheDiscriminator() {
        val albums = listOf(album(), album(type = "Single"), album(type = "EP", year = null), album(id = "zing:abc"))
        for (item in albums) assertEquals(item.toItem(), cardJson("album", item.toCard()), item.id)

        val artists = listOf(ArtistItem("UC9", "Name", "https://img/x.jpg", subscribers = "1.2M subscribers"), ArtistItem("UC8", "Bare", null))
        for (item in artists) assertEquals(item.toItem(), cardJson("artist", item.toCard()), item.id)

        val playlists =
            listOf(
                PlaylistItem("PL1", "List", "https://img/p.jpg", author = "Me", songCount = "42 songs"),
                PlaylistItem("PL2", "Local", null, isLocal = true, subtitle = "Sub"),
            )
        for (item in playlists) assertEquals(item.toItem(), cardJson("playlist", item.toCard()), item.id)
    }

    @Test
    fun albumKinds() {
        assertEquals("single", albumKind("Single"))
        assertEquals("ep", albumKind("EP"))
        assertEquals("album", albumKind("Album"))
        assertEquals("album", albumKind(null))
    }

    @Test
    fun albumResultSumsDurationAndFillsTrackAlbumAndArt() {
        val page = AlbumPage(album = album(), songs = listOf(song("a", 100), song("b", 80), song("c", null)), description = "About", artistAlbums = listOf(album("MPREb_2")))
        val result = PageMappers.albumResult(page)
        val card = result.getValue("album").jsonObject
        assertEquals("MPREb_1", card.getValue("id").jsonPrimitive.content)
        assertEquals(180_000L, card.getValue("durationMs").jsonPrimitive.long)
        assertEquals(3, card.getValue("trackCount").jsonPrimitive.int)
        assertEquals(2020, card.getValue("year").jsonPrimitive.int)
        assertEquals("About", card.getValue("description").jsonPrimitive.content)
        assertFalse(card.getValue("saved").jsonPrimitive.boolean)
        val first = result.getValue("tracks").jsonArray[0].jsonObject
        assertEquals("MPREb_1", first.getValue("album").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("https://img/a.jpg", first.getValue("art").jsonObject.getValue("src").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), result.getValue("otherVersions"))
        val moreBy = result.getValue("moreBy").jsonObject
        assertEquals("rows", moreBy.getValue("layout").jsonPrimitive.content)
        assertEquals("album", moreBy.getValue("items").jsonArray[0].jsonObject.getValue("t").jsonPrimitive.content)
    }

    @Test
    fun albumWithoutExtrasOmitsMoreByAndDefaultsYear() {
        val result = PageMappers.albumResult(AlbumPage(album = album(year = null), songs = emptyList()))
        assertNull(result["moreBy"])
        assertEquals(0, result.getValue("album").jsonObject.getValue("year").jsonPrimitive.int)
        assertEquals(0L, result.getValue("album").jsonObject.getValue("durationMs").jsonPrimitive.long)
    }

    @Test
    fun artistResultSplitsHeaderFromShelvesAndKeepsKeysUnique() {
        val header = ArtistItem("UC1", "Artist One", "https://img/art.jpg", subscribers = "10M", banner = "https://img/banner.jpg", monthlyListeners = "5M listeners")
        val page =
            ArtistPageResult(
                sections =
                    linkedMapOf(
                        "header" to listOf(header),
                        "Songs" to listOf(song("a"), song("b")),
                        "Videos" to listOf(song("v")),
                        "Albums" to listOf(album()),
                        "Empty" to emptyList(),
                    ),
                sectionBrowseIds = mapOf("Songs" to ("VLOLAK5uy_x" to null), "Videos" to ("VLOLAK5uy_x" to null)),
            )
        val result = PageMappers.artistResult(page, "UC1")
        val card = result.getValue("artist").jsonObject
        assertEquals("Artist One", card.getValue("name").jsonPrimitive.content)
        assertEquals("10M", card.getValue("subscribers").jsonPrimitive.content)
        assertEquals("5M listeners", card.getValue("monthly").jsonPrimitive.content)
        assertEquals("https://img/banner.jpg", card.getValue("banner").jsonObject.getValue("src").jsonPrimitive.content)
        assertFalse(card.getValue("subscribed").jsonPrimitive.boolean)
        assertEquals(JsonArray(listOf(JsonPrimitive("yt"))), result.getValue("sources"))
        val shelves = result.getValue("shelves").jsonArray.map { it.jsonObject }
        assertEquals(listOf("VLOLAK5uy_x", "VLOLAK5uy_x-2", "albums"), shelves.map { it.getValue("key").jsonPrimitive.content })
        assertEquals(listOf("rows", "cards", "cards"), shelves.map { it.getValue("layout").jsonPrimitive.content })
    }

    @Test
    fun artistWithoutHeaderFallsBackToTheRequestedId() {
        val result = PageMappers.artistResult(ArtistPageResult(sections = emptyMap()), "UC77")
        assertEquals("UC77", result.getValue("artist").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), result.getValue("shelves"))
    }

    @Test
    fun aboutParsesCounts() {
        assertEquals(1234567L, PageMappers.parseCount("1,234,567"))
        assertNull(PageMappers.parseCount("n/a"))
        assertNull(PageMappers.parseCount(null))
        val result = PageMappers.aboutResult(LastFmArtistData(tags = listOf("rock", "pop"), summary = "Bio", listeners = "1,000", scrobbles = "2,000,000"))
        assertEquals("Bio", result.getValue("bio").jsonPrimitive.content)
        val lastfm = result.getValue("lastfm").jsonObject
        assertEquals(1000L, lastfm.getValue("listeners").jsonPrimitive.long)
        assertEquals(2_000_000L, lastfm.getValue("playcount").jsonPrimitive.long)
        assertEquals(2, lastfm.getValue("tags").jsonArray.size)
        assertEquals(JsonObject(emptyMap()), PageMappers.aboutResult(null))
        assertNull(PageMappers.aboutResult(LastFmArtistData()).get("lastfm"))
    }

    @Test
    fun playlistResultCarriesRevisionAndNext() {
        val header = PlaylistItem("PL1", "List", null, author = "Me", description = "d")
        val songs = listOf(song("a"), song("b"))
        val result = PageMappers.playlistResult("PL1", header, songs, songs.drop(1), hasMore = true, next = "cursor1")
        val playlist = result.getValue("playlist").jsonObject
        assertEquals("List", playlist.getValue("title").jsonPrimitive.content)
        assertEquals("d", playlist.getValue("description").jsonPrimitive.content)
        assertFalse(playlist.getValue("editable").jsonPrimitive.boolean)
        assertEquals(listOf("a", "b").hashCode(), playlist.getValue("revision").jsonPrimitive.int)
        assertNull(playlist["durationMs"]) // more pages to come: the sum would mislead
        assertEquals(listOf("b"), result.getValue("tracks").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals("cursor1", result.getValue("next").jsonPrimitive.content)
    }

    @Test
    fun completePlaylistReportsDurationAndNoNext() {
        val songs = listOf(song("a", 60), song("b", 30))
        val result = PageMappers.playlistResult("PL1", null, songs, songs, hasMore = false, next = null)
        assertEquals(90_000L, result.getValue("playlist").jsonObject.getValue("durationMs").jsonPrimitive.long)
        assertTrue(result.getValue("next") is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun relatedAndChartsAndMoods() {
        assertEquals(JsonArray(emptyList()), PageMappers.relatedResult(emptyList()).getValue("shelves"))
        val shelf = PageMappers.relatedResult(listOf(song("a"))).getValue("shelves").jsonArray[0].jsonObject
        assertEquals("related", shelf.getValue("key").jsonPrimitive.content)

        val chart = BrowseApi.chartResult("zing", "Top", listOf("zing_0" to "Top", "zing_1" to "New"), listOf(song("a"), song("a"), song("b")), now = 5L)
        assertEquals(5L, chart.getValue("updatedAt").jsonPrimitive.long)
        assertEquals(2, chart.getValue("tabs").jsonArray.size)
        val items = chart.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b"), items.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf(1, 2), items.map { it.getValue("rank").jsonPrimitive.int })
        assertEquals("same", items[0].getValue("trend").jsonPrimitive.content)
        assertNull(items[0]["t"])
        assertEquals(2, ChartMapper.items(listOf(song("a"), song("b"))).size)

        val moods = BrowseApi.moodItems(listOf(BrowseItem("ggMPOg1u", "Chill", params = "p1"), BrowseItem("ggMPOg1v", "Focus")))
        assertEquals("ggMPOg1u|p1", moods[0].getValue("key").jsonPrimitive.content)
        assertEquals("ggMPOg1v|", moods[1].getValue("key").jsonPrimitive.content)
    }
}
