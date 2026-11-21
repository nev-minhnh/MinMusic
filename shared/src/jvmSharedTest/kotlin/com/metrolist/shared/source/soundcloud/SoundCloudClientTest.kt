package com.metrolist.shared.source.soundcloud

import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization

/** Recorded, reduced api-v2 responses from public unauthenticated SoundCloud endpoints (2026-11-21). */
class SoundCloudClientTest {
    private val downloader = FixtureDownloader()
    private val now = 1_800_000_000_000L
    private val client = SoundCloudClient(initialize = {}, clock = { now })

    @BeforeTest
    fun setUp() {
        NewPipe.init(downloader, Localization("vi", "VN"))
    }

    @Test
    fun searchMapsTracksAndPagesWithFreshClientId() =
        runBlocking {
            downloader.api = { url ->
                if (url.startsWith("${API}search/tracks?q=lofi&") && url.endsWith(CLIENT_ID_PARAM)) fixture("search_tracks.json") else null
            }
            val page = client.search("lofi", SoundCloudSearchFilter.TRACKS)

            val tracks = page.items.map { (it as SoundCloudSearchItem.Track).track }
            assertEquals(listOf("sc:2398021050", "sc:1655343951"), tracks.map { it.id })
            val first = tracks[0]
            assertEquals("Giá Như Anh Là Em |Lofi .| - Lệ Quyên", first.title)
            assertEquals("Olise", first.artist)
            assertEquals(216144L, first.durationMs)
            assertEquals("https://i1.sndcdn.com/artworks-Q6DOEbZ6HmKyhJPL-qiLKyQ-t500x500.jpg", first.artworkUrl)
            assertEquals("https://soundcloud.com/abcxyz16/gi-nh-anh-l-em-lofi-hot-tiktok", first.permalinkUrl)
            assertNull(first.album)
            assertTrue(first.isPlayable)
            val second = tracks[1]
            assertEquals("Fixture Album", second.album)
            // No track artwork: the website shows the uploader avatar instead.
            assertEquals("https://i1.sndcdn.com/avatars-lx5ttFnlPzApzBap-3fJi0A-t500x500.jpg", second.artworkUrl)

            val token = requireNotNull(page.nextPageToken)
            downloader.api = { url -> if (url == "$token&$CLIENT_ID_PARAM") """{"collection":[],"next_href":null}""" else null }
            val next = client.search("lofi", SoundCloudSearchFilter.TRACKS, token)
            assertTrue(next.items.isEmpty())
            assertNull(next.nextPageToken)

            assertFailsWith<IllegalArgumentException> {
                runBlocking { client.search("lofi", SoundCloudSearchFilter.TRACKS, "https://example.com/next") }
            }
            Unit
        }

    @Test
    fun playlistFillsStubTracksInPlaylistOrder() =
        runBlocking {
            downloader.api = { url ->
                when {
                    url.startsWith("${API}resolve?url=$PLAYLIST_URL&") -> fixture("playlist.json")
                    url.startsWith("${API}tracks?ids=951866101,1080322432&") -> fixture("tracks_lookup.json")
                    else -> null
                }
            }
            val playlist = client.playlist("https://m.soundcloud.com/lofi_chill/sets/lofi-chill/?si=abc")

            assertEquals(352480527L, playlist.summary.numericId)
            assertEquals("LoFi", playlist.summary.artist)
            assertFalse(playlist.summary.isAlbum)
            // The lookup response is unordered; the playlist order must win.
            assertEquals(
                listOf("sc:1004445667", "sc:1345949935", "sc:951866101", "sc:1080322432"),
                playlist.tracks.map { it.id },
            )
        }

    @Test
    fun chartsUnwrapTracksAndKeepPolicies() =
        runBlocking {
            downloader.api = { url -> if (url.startsWith("${API}charts?kind=trending&")) fixture("charts.json") else null }
            val page = client.charts()

            assertEquals(listOf(2320209185L, 2274228401L), page.items.map { it.numericId })
            assertEquals(SoundCloudPolicy.SNIP, page.items[0].policy)
            assertFalse(page.items[0].isPlayable)
            assertTrue(page.nextPageToken!!.startsWith("${API}charts?"))
        }

    @Test
    fun streamPrefersAac160HlsAndSetsExpiry() =
        runBlocking {
            downloader.api = trackRoutes(2398021050L, fixture("track_aac160.json"))
            val stream = client.stream("sc:2398021050")

            assertEquals("aac_160k", stream.preset)
            assertTrue(stream.url.contains("6e3f518f-5ac1-49b4-80d8-a587837a5c35"))
            assertTrue(stream.isHls)
            assertEquals("application/x-mpegURL", stream.mimeType)
            assertEquals("aac", stream.codec)
            assertEquals(160, stream.bitrateKbps)
            assertEquals(now + 4 * 60 * 1000L, stream.expiresAtMs)
        }

    @Test
    fun streamNeverResolvesEncryptedTranscodings() =
        runBlocking {
            // The encrypted aac_160k transcoding is unroutable: requesting it fails the test.
            downloader.api = trackRoutes(215764028L, fixture("track_drm_aac96.json"))
            val stream = client.stream("215764028")

            assertEquals("aac_96k", stream.preset)
            assertEquals(96, stream.bitrateKbps)
        }

    @Test
    fun previewAndBlockedTracksMapToTypedFailures() {
        downloader.api = trackRoutes(2181406707L, fixture("track_snip.json"))
        assertFailsWith<SoundCloudException.PreviewOnly> { runBlocking { client.stream("sc:2181406707") } }

        downloader.api = trackRoutes(2181406707L, fixture("track_snip.json").replace("\"policy\": \"SNIP\"", "\"policy\": \"BLOCK\""))
        assertFailsWith<SoundCloudException.Blocked> { runBlocking { client.stream("sc:2181406707") } }
    }

    @Test
    fun selectorFollowsPreferenceOrder() {
        val all =
            listOf(
                SoundCloudStreamCandidate("abr_sq", "u-abr", isHls = true),
                SoundCloudStreamCandidate("mp3_1_0", "u-mp3-hls", isHls = true),
                SoundCloudStreamCandidate("mp3_1_0", "u-mp3", isHls = false),
                SoundCloudStreamCandidate("opus_0_0", "u-opus-hls", isHls = true),
                SoundCloudStreamCandidate("opus_0_0", "u-opus", isHls = false),
                SoundCloudStreamCandidate("aac_96k", "u-96", isHls = true),
                SoundCloudStreamCandidate("aac_160k", "u-160", isHls = true),
            )
        val order = mutableListOf<String>()
        var remaining = all
        while (true) {
            val chosen = SoundCloudStreamSelector.select(remaining, now) ?: break
            assertEquals(now + SoundCloudStreamSelector.STREAM_URL_LIFETIME_MS, chosen.expiresAtMs)
            order += chosen.url
            remaining = remaining.filterNot { it.url == chosen.url }
        }
        assertEquals(listOf("u-160", "u-96", "u-opus", "u-opus-hls", "u-mp3", "u-mp3-hls"), order)
        assertEquals("audio/ogg", SoundCloudStreamSelector.select(all.filter { it.url == "u-opus" }, now)!!.mimeType)
        assertEquals("audio/mpeg", SoundCloudStreamSelector.select(all.filter { it.url == "u-mp3" }, now)!!.mimeType)
    }

    @Test
    fun selectorPrefersAac160RegardlessOfListingOrder() {
        // api-v2 lists aac_160k (quality "sq", free) before aac_96k ("lq"); abr_sq resolves to {} for public clients.
        val listed =
            listOf(
                SoundCloudStreamCandidate("aac_160k", "u-160", isHls = true),
                SoundCloudStreamCandidate("aac_96k", "u-96", isHls = true),
                SoundCloudStreamCandidate("abr_sq", "u-abr", isHls = true),
            )
        for (candidates in listOf(listed, listed.reversed())) {
            val chosen = SoundCloudStreamSelector.select(candidates, now)!!
            assertEquals("aac_160k", chosen.preset)
            assertEquals(160, chosen.bitrateKbps)
        }
        // Some uploads (e.g. sc:1056337348, the 2026-11-21 QA track) only offer aac_96k + abr_sq: 96 is the best there is.
        val only96 = SoundCloudStreamSelector.select(listed.drop(1), now)!!
        assertEquals("aac_96k", only96.preset)
        assertEquals(96, only96.bitrateKbps)
    }

    private fun trackRoutes(
        id: Long,
        trackJson: String,
    ): (String) -> String? =
        { url ->
            when {
                url.startsWith("${API}resolve?url=https%3A%2F%2Fapi.soundcloud.com%2Ftracks%2F$id&") -> trackJson
                url.startsWith("${API}media/soundcloud:tracks:$id/") && !url.contains("encrypted") -> {
                    val transcoding = url.substringAfter("soundcloud:tracks:$id/").substringBefore('/')
                    """{"url":"https://playback.media-streaming.soundcloud.cloud/fixture/$transcoding/playlist.m3u8"}"""
                }
                else -> null
            }
        }

    private class FixtureDownloader : Downloader() {
        var api: (String) -> String? = { null }

        override fun execute(request: Request): Response {
            val url = request.url()
            val body =
                when {
                    url == "https://soundcloud.com" -> fixture("home.html")
                    url.startsWith("https://a-v2.sndcdn.com/assets/") -> fixture("asset.js")
                    else -> api(url) ?: throw AssertionError("Unexpected request: $url")
                }
            return Response(200, "OK", emptyMap(), body, url)
        }
    }

    private companion object {
        const val API = "https://api-v2.soundcloud.com/"
        const val CLIENT_ID_PARAM = "client_id=fixtureClientId0123456789abcdef"
        const val PLAYLIST_URL = "https%3A%2F%2Fsoundcloud.com%2Flofi_chill%2Fsets%2Flofi-chill"

        fun fixture(name: String): String =
            requireNotNull(SoundCloudClientTest::class.java.getResourceAsStream("/soundcloud/$name")) { name }
                .bufferedReader()
                .use { it.readText() }
    }
}
