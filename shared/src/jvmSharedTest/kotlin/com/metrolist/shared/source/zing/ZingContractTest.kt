package com.metrolist.shared.source.zing

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZingContractTest {
    // Expected values were computed with PerformanC/NodeLink src/sources/zingmp3.ts buildSignature (run verbatim in
    // Node) and ElectroHeavenVN/ZingMP3Explode ZingMP3APIClient.Sign (ported to Python); both agree. The streaming
    // and search URLs were also accepted by zingmp3.vn (err 0) on 2026-11-21.
    @Test
    fun signatureMatchesReferenceImplementations() {
        val secret = ZingKeyProvider.DEFAULT_KEYS.secret
        assertEquals(
            "b80c340d9c642c3668f6e14e1687163aa2e0c770df3a07abe69d646a190dd68dc7f5bc3ed8392639841fd2dcd933a50cf749faa24c24e78fd2627ebeb2760ccd",
            ZingSigner.sign(
                "/api/v2/song/get/streaming",
                mapOf("id" to "d7qtCrRV89zn", "ctime" to "1795235475", "version" to "1.22.4"),
                secret,
            ),
        )
        assertEquals(
            "0e2f4f6cd3d693f4d7d93451e62c314e45e2a6a750f3e783ab7bb69b06c5b11ee45c24f4447361d2e78ab3b4223d3a45b57b7510678eed8ffa041a32b496403d",
            ZingSigner.sign(
                "/api/v2/page/get/chart-home",
                mapOf("ctime" to "1795235475", "version" to "1.22.4", "id" to ""),
                secret,
            ),
        )
        val search =
            ZingApi
                .buildUrl(
                    ZingEndpoint.SEARCH,
                    mapOf("q" to "son tung", "type" to "song", "page" to "1", "count" to "18"),
                    ZingKeyProvider.DEFAULT_KEYS,
                    1795235475,
                ).toHttpUrl()
        assertEquals("/api/v2/search", search.encodedPath)
        assertEquals("son tung", search.queryParameter("q"))
        assertEquals(ZingKeyProvider.DEFAULT_KEYS.apiKey, search.queryParameter("apiKey"))
        assertEquals(
            "a254be90e5bcd06290be2bb23ca988609806abdee1571b1d6fa0f084cd7712566056f55faf9f47a7e6b3c3517e864c5fc0b63523bd8b30d4fed7b670f23f59e9",
            search.queryParameter("sig"),
        )
    }

    @Test
    fun keysAreExtractedFromSavedPageAndBundle() {
        val script = assertNotNull(ZingKeyExtractor.findMainScript(fixture("home-excerpt.html")))
        assertEquals("https://zmdjs.zmdcdn.me/zmp3-desktop/v1.22.4/static/js/main.min.js", script.url)
        assertEquals("1.22.4", script.version)
        val (apiKey, secret) = assertNotNull(ZingKeyExtractor.findKeys(fixture("main-min-excerpt.js")))
        assertEquals("X5BM3w8N7MKozC0B85o4KMlzLZKhV00y", apiKey)
        assertEquals("acOrvUS15XRW2o9JksiK1KgQ6Vbds8ZW", secret)
    }

    @Test
    fun songInfoMapsToNamespacedMetadata() {
        val song = assertNotNull(ZingMapper.song(data("song-info.json")))
        assertEquals("zing:d7qtCrRV89zn", song.id)
        assertEquals("Nơi Này Có Anh", song.title)
        assertEquals(listOf("Sơn Tùng M-TP"), song.artists.map { it.name })
        assertEquals("zing:ODpMjF8B4Xh2", song.album?.id)
        assertEquals(262, song.durationSeconds)
        assertEquals(false, song.isWorldWide)
        assertTrue(song.artworkUrl!!.contains("/w600_r1x1_jpeg/"))
    }

    @Test
    fun freeStreamingResponseNeverSelectsVipTier() {
        val data = data("streaming-free.json")
        assertEquals(listOf(ZingQuality.KBPS_128), ZingMapper.streams(data).map { it.quality })
        val stream = ZingMapper.selectStream(data, preferLossless = true)
        assertEquals(ZingQuality.KBPS_128, stream.quality)
        assertEquals("audio/mpeg", stream.mimeType)
        assertEquals(128, stream.bitrateKbps)
        assertEquals(1795408195000, stream.expiresAtMs)
        assertTrue(stream.url.startsWith("https://a128-z3.zmdcdn.me/"))
    }

    @Test
    fun tierOrderIsLosslessThen320Then128AmongUrlsOnly() {
        val vipAccount =
            parse("""{"128":"https://a128.zmdcdn.me/a?authen=exp=1","320":"https://a320.zmdcdn.me/b","lossless":"VIP"}""")
        assertEquals(ZingQuality.KBPS_320, ZingMapper.selectStream(vipAccount, preferLossless = true).quality)
        val lossless = parse("""{"128":"https://a.zmdcdn.me/a","320":"https://a.zmdcdn.me/b","lossless":"https://a.zmdcdn.me/c"}""")
        val best = ZingMapper.selectStream(lossless, preferLossless = true)
        assertEquals(ZingQuality.LOSSLESS, best.quality)
        assertEquals("audio/flac", best.mimeType)
        assertEquals(ZingQuality.KBPS_320, ZingMapper.selectStream(lossless, preferLossless = false).quality)
        assertFailsWith<ZingException.AuthRequired> {
            ZingMapper.selectStream(parse("""{"128":"VIP","320":"VIP"}"""), preferLossless = false)
        }
    }

    @Test
    fun apiErrorsMapToTypedExceptions() {
        val geo = ZingException.fromApi(-1150, "Nội dung này không tải được cho quốc gia của bạn!")
        assertIs<ZingException.GeoBlocked>(geo)
        assertIs<ZingException.NotFound>(ZingException.fromApi(-1023, "Không tìm thấy bài hát này."))
        assertIs<ZingException.Signature>(ZingException.fromApi(-104, "Incorect signature"))
    }

    @Test
    fun listingsAndProfileMap() {
        val search = ZingMapper.searchResult(data("search-song.json"), ZingSearchType.SONG, page = 1, pageSize = 18)
        assertEquals(listOf("zing:d7qtCrRV89zn", "zing:yTPspCIlrm0y"), search.songs.map { it.id })
        assertEquals(119, search.total)
        assertTrue(search.hasMore)
        val playlist = ZingMapper.playlist(data("playlist.json"))
        assertTrue(playlist.isAlbum)
        assertEquals(listOf("zing:d7qtCrRV89zn"), playlist.songs.map { it.id })
        assertNull(ZingMapper.profileUserId(data("profile-guest.json")))
    }

    @Test
    fun lyricsFollowDataFileAfterSessionRefresh() =
        runTest {
            val lyricFile = "https://static-zmp3.zmdcdn.me/lyrics/2017/04/10/437fd8dab336d63566e90d61e2dde4ea_1075841896.lrc"
            var lyricCalls = 0
            val http =
                ZingHttp { url, headers ->
                    assertFalse(headers.keys.any { it.equals("X-Forwarded-For", ignoreCase = true) })
                    when {
                        url == ZingApi.HOME_URL ->
                            ZingHttpResponse(
                                200,
                                fixture("home-excerpt.html"),
                                listOf("zmp3_rqid=visitor;Path=/;Domain=.zingmp3.vn;Secure;HttpOnly"),
                            )
                        url.endsWith("/static/js/main.min.js") -> ZingHttpResponse(200, fixture("main-min-excerpt.js"))
                        url.startsWith("https://zingmp3.vn/api/v2/lyric/get/lyric?") -> {
                            assertEquals("zmp3_rqid=visitor", headers["Cookie"])
                            lyricCalls++
                            if (lyricCalls == 1) {
                                ZingHttpResponse(200, """{"err":-201,"msg":"Có lỗi xảy ra xin vui lòng thử lại!"}""")
                            } else {
                                ZingHttpResponse(200, fixture("lyric.json"))
                            }
                        }
                        url == lyricFile -> ZingHttpResponse(200, fixture("lyric.lrc"))
                        else -> error("Unexpected request")
                    }
                }
            val session = ZingSession(http)
            val api = ZingApi(http, ZingKeyProvider(http), session)
            val lyrics = assertNotNull(ZingClient(api, http, session).lyrics("zing:d7qtCrRV89zn"))
            // Fixtures may be checked out with CRLF on Windows; compare LRC text independent of line endings.
            assertEquals(fixture("lyric.lrc").replace("\r\n", "\n").trim(), lyrics.replace("\r\n", "\n"))
            assertTrue(lyrics.contains("[00:22.91]Em là ai từ đâu bước đến nơi đây"))
            assertEquals(2, lyricCalls)
        }

    @Test
    fun wordTimingsBuildLrcWhenFileIsMissing() {
        val lrc = assertNotNull(ZingMapper.lrcFromSentences(data("lyric.json")))
        assertEquals("[00:22.80]Em là ai từ đâu bước đến nơi đây\n[00:25.70]Dịu dàng chân phương", lrc)
    }

    private fun fixture(name: String): String =
        assertNotNull(javaClass.getResourceAsStream("/zing/$name"), name).bufferedReader().use { it.readText() }

    private fun data(name: String): JsonObject = Json.parseToJsonElement(fixture(name)).jsonObject["data"]!!.jsonObject

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
