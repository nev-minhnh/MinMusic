package com.metrolist.core.api.library

import com.metrolist.core.api.ApiRegistry
import com.metrolist.core.api.HomeApi
import com.metrolist.core.api.browse.BrowseApi
import com.metrolist.core.api.queue.QueueMapper
import com.metrolist.core.api.search.SearchMapper
import com.metrolist.core.server.ApiException
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.SongItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The contract fixes of INT-W1 that are pure: queue ranges and unknown ids, search history, home fixture chips, moods, quality labels. */
class Wave2FixesTest {
    // --- queue ----------------------------------------------------------------------------------------------------

    @Test
    fun queueWindowRangeIsValidated() {
        QueueMapper.checkRange(0, 1)
        QueueMapper.checkRange(10, 200)
        for ((offset, limit) in listOf(0 to 201, 0 to 500, 0 to 0, 0 to -1, -1 to 10)) {
            assertEquals("bad_request", assertFailsWith<ApiException> { QueueMapper.checkRange(offset, limit) }.code, "$offset/$limit")
        }
    }

    @Test
    fun onlyTheBareFallbackOfAYoutubeIdIsUnresolved() {
        val bare = SongItem(id = "no-such-track", title = "no-such-track", thumbnail = null, artists = emptyList(), album = null)
        assertTrue(QueueMapper.isUnresolved(bare))
        assertFalse(QueueMapper.isUnresolved(bare.copy(artists = listOf(ArtistTiny(null, "Someone")))))
        assertFalse(QueueMapper.isUnresolved(bare.copy(title = "A real title")))
        // other sources keep the bare-id fallback: the source resolves the stream by id
        assertFalse(QueueMapper.isUnresolved(bare.copy(id = "zing:ZW1", title = "zing:ZW1")))
    }

    // --- search history ---------------------------------------------------------------------------------------------

    @Test
    fun searchHistoryPutsTheNewestFirstWithoutDuplicates() {
        assertEquals(listOf("b", "a"), SearchMapper.addToHistory(listOf("a"), "b"))
        assertEquals(listOf("a", "b"), SearchMapper.addToHistory(listOf("b", "a"), "a"))
        assertEquals(listOf("a"), SearchMapper.addToHistory(emptyList(), " a "))
        assertEquals(listOf("a"), SearchMapper.addToHistory(listOf("a"), "  "))
    }

    @Test
    fun searchHistoryIsCapped() {
        val history = (1..SearchMapper.MAX_HISTORY).map { "q$it" }
        val next = SearchMapper.addToHistory(history, "new")
        assertEquals(SearchMapper.MAX_HISTORY, next.size)
        assertEquals("new", next.first())
        assertFalse("q${SearchMapper.MAX_HISTORY}" in next)
    }

    // --- home fixture ------------------------------------------------------------------------------------------------

    private val fixture: JsonObject =
        Json
            .parseToJsonElement(
                """{"shelves":[],"chips":[{"key":"relax","title":"R","selected":false},{"key":"focus","title":"F","selected":false}],"greeting":{"key":"good_evening"},"fromCache":true,"next":null}""",
            ).jsonObject

    @Test
    fun fixtureWithoutAChipIsReturnedAsWritten() {
        assertSame(fixture, HomeApi.selectFixtureChip(fixture, null))
    }

    @Test
    fun fixtureMarksExactlyTheRequestedChip() {
        val chips = HomeApi.selectFixtureChip(fixture, "focus").jsonObject["chips"]!!.jsonArray
        assertEquals(listOf("relax" to false, "focus" to true), chips.map { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["selected"]!!.jsonPrimitive.boolean })
        val again = HomeApi.selectFixtureChip(HomeApi.selectFixtureChip(fixture, "focus"), "relax").jsonObject["chips"]!!.jsonArray
        assertEquals(listOf(true, false), again.map { it.jsonObject["selected"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun fixtureKeepsEverythingElseAndIgnoresUnknownChips() {
        val marked = HomeApi.selectFixtureChip(fixture, "focus").jsonObject
        assertEquals(fixture["shelves"], marked["shelves"])
        assertEquals(fixture["greeting"], marked["greeting"])
        assertEquals(JsonPrimitive(true), marked["fromCache"])
        assertSame(fixture, HomeApi.selectFixtureChip(fixture, "no-such-chip"))
    }

    // --- moods ---------------------------------------------------------------------------------------------------------

    @Test
    fun moodKeysAreUniqueAndUntitledEntriesDropped() {
        val moods =
            BrowseApi.moodItems(
                listOf(
                    BrowseItem("id1", "Chill", params = "p"),
                    BrowseItem("id1", "Chill again", params = "p"),
                    BrowseItem("id1", "Other params", params = "q"),
                    BrowseItem("id2", " "),
                    BrowseItem("id3", "Focus"),
                ),
            )
        assertEquals(listOf("id1|p", "id1|q", "id3|"), moods.map { it["key"]!!.jsonPrimitive.content })
        assertEquals("Chill", moods[0]["title"]!!.jsonPrimitive.content)
    }

    // --- quality labels --------------------------------------------------------------------------------------------------

    @Test
    fun qualityLabelsFromAStoredFormat() {
        assertEquals("Opus 160", QualityLabel.of("opus", 160_000).label)
        assertEquals("AAC 128", QualityLabel.of("mp4a.40.2", 127_900).label)
        assertEquals(128, QualityLabel.of("mp4a.40.2", 127_900).kbps)
        assertTrue(QualityLabel.of("flac", 900_000).lossless)
        assertEquals("FLAC", QualityLabel.of("flac", 900_000).label)
        assertEquals("Lossless", QualityLabel.of("alac", null).label)
        assertEquals("320", QualityLabel.of("mp3", 320_000).label)
        assertEquals("Auto", QualityLabel.of(null, null).label)
        assertFalse(QualityLabel.of("vorbis", 96_000).lossless)
    }

    // --- registration ------------------------------------------------------------------------------------------------------

    @Test
    fun theLibraryModuleRegistersItsMethodsAndTopic() {
        val (handlers, topics) = ApiRegistry.load()
        val expected =
            listOf(
                "library.counts", "library.window", "library.indexOf", "library.sync", "track.like", "album.save", "artist.subscribe",
                "playlist.save", "track.info", "track.sources", "track.setSource", "track.block", "artist.block", "userAlbum.create",
                "userAlbum.rename", "userAlbum.delete", "userAlbum.setCover", "userAlbum.add", "userAlbum.remove", "userAlbum.move",
                "userAlbum.pin", "userAlbum.list", "history.window", "history.remove", "history.clear", "stats.get", "import.files",
            )
        for (method in expected) assertTrue(method in handlers, "missing $method")
        assertTrue("library" in topics)
    }
}
