package com.metrolist.core.api

import com.metrolist.core.api.search.SearchCursor
import com.metrolist.core.api.search.SearchMapper
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.source.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchCursorTest {
    @Test
    fun roundTrip() {
        assertEquals("zing:3", SearchCursor.encode(SourceId.ZING, 3))
        assertEquals(SourceId.ZING to 3, SearchCursor.parse("zing:3"))
        assertEquals(SourceId.NCT to 0, SearchCursor.parse(SearchCursor.encode(SourceId.NCT, 0)))
        assertEquals(SourceId.SC to 12, SearchCursor.parse(SearchCursor.encode(SourceId.SC, 12)))
    }

    @Test
    fun youtubeTokensAndGarbageAreNotSourceCursors() {
        assertNull(SearchCursor.parse(null))
        assertNull(SearchCursor.parse(""))
        assertNull(SearchCursor.parse(":3"))
        assertNull(SearchCursor.parse("zing:"))
        assertNull(SearchCursor.parse("zing:x"))
        assertNull(SearchCursor.parse("zing:-1"))
        assertNull(SearchCursor.parse("yt:3"))
        assertNull(SearchCursor.parse("local:3"))
        assertNull(SearchCursor.parse("EqWUARIEZ29vZw%3D%3D"))
    }

    @Test
    fun typeToFilter() {
        assertEquals(SearchFilter.SONG, SearchMapper.filterOf("track"))
        assertEquals(SearchFilter.ALBUM, SearchMapper.filterOf("album"))
        assertEquals(SearchFilter.ARTIST, SearchMapper.filterOf("artist"))
        assertEquals(SearchFilter.FEATURED_PLAYLIST, SearchMapper.filterOf("playlist"))
        assertNull(SearchMapper.filterOf("all"))
        assertNull(SearchMapper.filterOf("video"))
    }

    @Test
    fun historyRemoval() {
        val history = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), SearchMapper.removeFromHistory(history, "b"))
        assertEquals(history, SearchMapper.removeFromHistory(history, "zzz"))
        assertEquals(emptyList(), SearchMapper.removeFromHistory(history, null))
    }
}
