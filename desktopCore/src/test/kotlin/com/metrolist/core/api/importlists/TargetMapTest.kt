package com.metrolist.core.api.importlists

import com.metrolist.core.server.ApiException
import com.metrolist.shared.playlistimport.ImportSourceType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TargetMapTest {
    private fun code(block: () -> Unit): String = assertFailsWith<ApiException> { block() }.code

    @Test
    fun targets() {
        assertEquals(ImportTarget.NewUserAlbum, parseImportTarget("newUserAlbum"))
        assertEquals(ImportTarget.Existing("LPabc12345"), parseImportTarget("lp:LPabc12345"))
        assertEquals("bad_request", code { parseImportTarget("lp:") })
        assertEquals("bad_request", code { parseImportTarget("playlist") })
        assertEquals("bad_request", code { parseImportTarget(null) })
    }

    @Test
    fun providersInWireOrderWithSources() {
        assertEquals(listOf("spotify", "tidal", "deezer", "apple", "amazon"), ImportProviderId.entries.map { it.wire })
        assertEquals(ImportSourceType.APPLE_MUSIC, ImportProviderId.parse("apple").source)
        assertEquals(ImportSourceType.AMAZON, ImportProviderId.parse("amazon").source)
        assertEquals("bad_request", code { ImportProviderId.parse("nope") })
        assertEquals("bad_request", code { ImportProviderId.parse(null) })
    }

    @Test
    fun playlistIds() {
        val ids = JsonArray(listOf("a", "b", "a").map(::JsonPrimitive))
        assertEquals(listOf("a", "b"), parsePlaylistIds(ids))
        assertEquals("bad_request", code { parsePlaylistIds(JsonArray(emptyList())) })
        assertEquals("bad_request", code { parsePlaylistIds(null) })
        assertEquals("bad_request", code { parsePlaylistIds(JsonArray(listOf(JsonPrimitive(3)))) })
        assertEquals("bad_request", code { parsePlaylistIds(JsonArray(listOf(JsonPrimitive("")))) })
    }

    @Test
    fun resultAlbum() {
        assertEquals("lp:LPx", resultAlbumId(ImportTarget.Existing("LPx"), listOf("LPnew")))
        assertEquals("lp:LPa", resultAlbumId(ImportTarget.NewUserAlbum, listOf("LPa", "LPb")))
        assertNull(resultAlbumId(ImportTarget.NewUserAlbum, emptyList()))
    }

    @Test
    fun amazonHostComesFromTheFinalUrl() {
        assertEquals("music.amazon.de", ImportLoginConfigs.amazonHost("https://music.amazon.de/home", "music.amazon.com"))
        assertEquals("music.amazon.com", ImportLoginConfigs.amazonHost("https://evil.example/home", "music.amazon.com"))
        assertEquals("music.amazon.co.uk", ImportLoginConfigs.amazonHost(null, "music.amazon.co.uk"))
    }

    @Test
    fun rowJsonOmitsMissingArt() {
        assertEquals("""{"id":"x","title":"T","count":2}""", ImportRow("x", "T", 2, null).toJson().toString())
        assertEquals("""{"id":"x","title":"T","count":2,"art":"u"}""", ImportRow("x", "T", 2, "u").toJson().toString())
    }
}
