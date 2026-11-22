package com.metrolist.core.api

import com.metrolist.core.api.app.AppApi
import com.metrolist.core.server.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CopyLinkTest {
    @Test
    fun youtubeIds() {
        assertEquals("https://music.youtube.com/watch?v=abc123", AppApi.copyLink("abc123", "track"))
        assertEquals("https://music.youtube.com/watch?v=abc123", AppApi.copyLink("abc123", "song"))
        assertEquals("https://music.youtube.com/browse/MPREb_1", AppApi.copyLink("MPREb_1", "album"))
        assertEquals("https://music.youtube.com/channel/UC1", AppApi.copyLink("UC1", "artist"))
        assertEquals("https://music.youtube.com/playlist?list=PL1", AppApi.copyLink("PL1", "playlist"))
        assertEquals("https://music.youtube.com/playlist?list=PL1", AppApi.copyLink("VLPL1", "playlist"))
        assertEquals("https://music.youtube.com/browse/MPSP1", AppApi.copyLink("MPSP1", "podcast"))
    }

    @Test
    fun otherNamespacesAreUnsupported() {
        for (id in listOf("sc:123", "zing:ZW1", "nct:abc", "local_42", "lp:7")) {
            val error = assertFailsWith<ApiException>(id) { AppApi.copyLink(id, "track") }
            assertEquals("unsupported", error.code, id)
        }
    }

    @Test
    fun unknownKindIsBadRequest() {
        assertEquals("bad_request", assertFailsWith<ApiException> { AppApi.copyLink("abc", "banana") }.code)
    }
}
