package com.metrolist.core.api.download

import com.metrolist.core.api.AlbumRef
import com.metrolist.core.api.ArtistRef
import com.metrolist.core.api.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

class DownloadWindowTest {
    private fun row(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String? = null,
        at: Long = 0,
        plays: Long = 0,
        dur: Long? = null,
    ) = DownloadRow(
        Track(id = id, title = title, artists = listOf(ArtistRef(null, artist)), album = album?.let { AlbumRef(null, it) }, durationMs = dur, source = "yt"),
        at,
        plays,
    )

    private val rows =
        listOf(
            row("1", "Beta", "Zed", "Alpha Album", at = 300, plays = 5, dur = 200_000),
            row("2", "alpha", "Yan", null, at = 100, plays = 9, dur = 100_000),
            row("3", "Gamma", "Xi", "Alpha Album", at = 200, plays = 1, dur = 300_000),
        )

    private fun ids(
        by: String,
        desc: Boolean,
    ) = DownloadWindow.sorted(rows, by, desc).map { it.track.id }

    @Test
    fun sortsByEveryKey() {
        assertEquals(listOf("2", "1", "3"), ids("title", false))
        assertEquals(listOf("3", "1", "2"), ids("title", true))
        assertEquals(listOf("2", "3", "1"), ids("added", false))
        assertEquals(listOf("1", "3", "2"), ids("added", true))
        assertEquals(listOf("3", "2", "1"), ids("artist", false))
        assertEquals(listOf("2", "1", "3"), ids("duration", false))
        assertEquals(listOf("3", "1", "2"), ids("plays", false))
        // no album sorts first; the two "Alpha Album" rows tie and fall back to the id
        assertEquals(listOf("2", "1", "3"), ids("album", false))
    }

    @Test
    fun unknownSortFallsBackToAdded() {
        assertEquals(ids("added", false), ids("custom", false))
    }

    @Test
    fun textFilterIsCaseAndDiacriticInsensitive() {
        val vi = row("9", "Đi Về Nhà", "Sơn Tùng")
        assertTrue(DownloadWindow.matches(vi, DownloadFilter(text = "di ve nha")))
        assertTrue(DownloadWindow.matches(vi, DownloadFilter(text = "SON TUNG")))
        assertFalse(DownloadWindow.matches(vi, DownloadFilter(text = "xyz")))
        assertTrue(DownloadWindow.matches(rows[0], DownloadFilter(text = "alpha album")))
    }

    @Test
    fun windowSlicesAndReportsTheFilteredTotal() {
        val w = DownloadWindow.window(rows, "title", false, DownloadFilter(), offset = 1, limit = 1)
        assertEquals(3, w["total"]!!.jsonPrimitive.int)
        assertEquals(1, w["offset"]!!.jsonPrimitive.int)
        val items = w["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("1", items[0].jsonObject["id"]!!.jsonPrimitive.content)
        // text "alpha" hits one title and two albums
        val f = DownloadWindow.window(rows, "title", false, DownloadFilter(text = "alpha"), 0, 10)
        assertEquals(3, f["total"]!!.jsonPrimitive.int)
        val none = DownloadWindow.window(rows, "title", false, DownloadFilter(text = "nothing"), 0, 10)
        assertEquals(0, none["total"]!!.jsonPrimitive.int)
        assertEquals(JsonArray(emptyList()), none["items"])
    }

    @Test
    fun offsetPastTheEndIsEmptyNotAnError() {
        val w = DownloadWindow.window(rows, "title", false, DownloadFilter(), offset = 50, limit = 10)
        assertEquals(0, w["items"]!!.jsonArray.size)
        assertEquals(3, w["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun revisionIsStableAndChangesWithTheSet() {
        val a = DownloadWindow.revisionOf(rows)
        assertEquals(a, DownloadWindow.revisionOf(rows))
        assertTrue(a != DownloadWindow.revisionOf(rows.drop(1)))
        assertTrue(a in 0..0x1FFFFFFFFFFFFFL)
        val w = DownloadWindow.window(rows, "title", false, DownloadFilter(text = "zzz"), 0, 5)
        assertEquals(a, w["revision"]!!.jsonPrimitive.long)
    }

    @Test
    fun queueItemCarriesStateProgressAndError() {
        val t = Track(id = "x", title = "T", artists = emptyList(), source = "yt")
        val ok: JsonObject = QueueItemMapper.item(t, "downloading", 0.4, null)
        assertEquals("downloading", ok["state"]!!.jsonPrimitive.content)
        assertEquals("downloading", ok["download"]!!.jsonPrimitive.content)
        assertEquals(0.4, ok["progress"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertNull(ok["error"])
        val bad = QueueItemMapper.item(t, "failed", 7.0, "network")
        assertEquals("network", bad["error"]!!.jsonPrimitive.content)
        assertEquals(1.0, bad["progress"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }
}

class ErrorLabelTest {
    @Test
    fun mapsToTheCodesTheUiKnows() {
        assertEquals("network", ErrorLabel.of("Connection reset"))
        assertEquals("network", ErrorLabel.of(null))
        assertEquals("disk_full", ErrorLabel.of("There is not enough space: No space left on device"))
        assertEquals("not_writable", ErrorLabel.of("Access is denied"))
        assertEquals("unavailable", ErrorLabel.of("Failed to resolve stream URL"))
        assertEquals("not_found", ErrorLabel.of("HTTP 404 Not Found"))
    }
}
