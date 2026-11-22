package com.metrolist.core.api

import com.metrolist.core.api.app.AppApi
import com.metrolist.core.api.app.UrlTarget
import com.metrolist.innertubex.utils.YouTubeUrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenUrlTest {
    private val video = "R93xV3v718s"

    @Test
    fun navForMapsEveryParsedKind() {
        assertEquals(UrlTarget.Nav("album", "MPREb_1"), AppApi.navFor(YouTubeUrlParser.ParsedUrl.Album("MPREb_1")))
        assertEquals(UrlTarget.Nav("playlist", "PL1"), AppApi.navFor(YouTubeUrlParser.ParsedUrl.Playlist("PL1")))
        assertEquals(UrlTarget.Nav("artist", "UC1"), AppApi.navFor(YouTubeUrlParser.ParsedUrl.Artist("UC1")))
        assertEquals(UrlTarget.Play(video), AppApi.navFor(YouTubeUrlParser.ParsedUrl.Video(video)))
        assertNull(AppApi.navFor(null))
    }

    @Test
    fun podcastIdWinsOverAParsedUrl() {
        assertEquals(UrlTarget.Nav("podcast", "MPSPabc"), AppApi.navFor(null, "MPSPabc"))
        assertEquals(UrlTarget.Nav("podcast", "MPSPabc"), AppApi.navFor(YouTubeUrlParser.ParsedUrl.Album("MPREb_1"), "MPSPabc"))
    }

    @Test
    fun musicYoutubeWatch() {
        assertEquals(UrlTarget.Play(video), AppApi.resolveUrl("https://music.youtube.com/watch?v=$video"))
        assertEquals(UrlTarget.Play(video), AppApi.resolveUrl("https://music.youtube.com/watch?v=$video&list=RDAMVM$video"))
    }

    @Test
    fun musicYoutubePlaylistAlbumArtist() {
        assertEquals(UrlTarget.Nav("playlist", "PL1234567890abcdef"), AppApi.resolveUrl("https://music.youtube.com/playlist?list=PL1234567890abcdef"))
        assertEquals(UrlTarget.Nav("album", "MPREb_1234567890"), AppApi.resolveUrl("https://music.youtube.com/browse/MPREb_1234567890"))
        assertEquals(UrlTarget.Nav("artist", "UC1234567890abcdef"), AppApi.resolveUrl("https://music.youtube.com/channel/UC1234567890abcdef"))
    }

    @Test
    fun shortAndPlainYoutubeLinks() {
        assertEquals(UrlTarget.Play(video), AppApi.resolveUrl("https://youtu.be/$video"))
        assertEquals(UrlTarget.Play(video), AppApi.resolveUrl("https://www.youtube.com/watch?v=$video"))
    }

    @Test
    fun appSchemes() {
        assertEquals(UrlTarget.Play(video), AppApi.resolveUrl("minmusic://open?url=https%3A%2F%2Fmusic.youtube.com%2Fwatch%3Fv%3D$video"))
        assertEquals(UrlTarget.Nav("artist", "UC1234567890abcdef"), AppApi.resolveUrl("minmusic://music.youtube.com/channel/UC1234567890abcdef"))
        assertEquals(UrlTarget.Play(video), AppApi.resolveUrl("metrolist://watch?v=$video"))
        assertEquals(UrlTarget.Nav("album", "MPREb_1234567890"), AppApi.resolveUrl("metrolist://album/MPREb_1234567890"))
    }

    @Test
    fun metrolistWebLink() {
        assertEquals(UrlTarget.Play(video), AppApi.resolveUrl("https://metrolist.cc/watch?v=$video"))
    }

    @Test
    fun quotedAndPaddedInputIsAccepted() {
        assertEquals(UrlTarget.Play(video), AppApi.resolveUrl("  \"https://music.youtube.com/watch?v=$video\"  "))
    }

    @Test
    fun otherSourcesAndJunkAreNotHandled() {
        assertNull(AppApi.resolveUrl("https://zingmp3.vn/bai-hat/Abc/ZW67ABCD.html"))
        assertNull(AppApi.resolveUrl("https://www.nhaccuatui.com/bai-hat/abc.xyz.html"))
        assertNull(AppApi.resolveUrl("https://soundcloud.com/artist/track"))
        assertNull(AppApi.resolveUrl("just some words"))
        assertNull(AppApi.resolveUrl("C:\\Music\\song.mp3"))
        assertNull(AppApi.resolveUrl("https://example.com/"))
        assertNull(AppApi.resolveUrl(""))
        assertNull(AppApi.resolveUrl("   "))
    }
}
