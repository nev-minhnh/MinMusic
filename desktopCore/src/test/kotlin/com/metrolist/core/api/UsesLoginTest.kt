package com.metrolist.core.api

import com.metrolist.core.api.browse.BrowseCursor
import com.metrolist.core.api.browse.sourceUnavailable
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceUnavailableException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsesLoginTest {
    @Test
    fun libraryAndMoodsNeedTheSession() {
        assertTrue(BrowseCursor.usesLogin("FEmusic_library_landing"))
        assertTrue(BrowseCursor.usesLogin("FEmusic_library_corpus_track_artists"))
        assertTrue(BrowseCursor.usesLogin("FEmusic_moods_and_genres"))
        assertTrue(BrowseCursor.usesLogin("FEmusic_moods_and_genres_category"))
        assertTrue(BrowseCursor.usesLogin("VLSE"))
        assertTrue(BrowseCursor.usesLogin("VLRDPN"))
    }

    @Test
    fun publicPagesDoNot() {
        assertFalse(BrowseCursor.usesLogin("FEmusic_home"))
        assertFalse(BrowseCursor.usesLogin("FEmusic_charts"))
        assertFalse(BrowseCursor.usesLogin("FEmusic_new_releases"))
        assertFalse(BrowseCursor.usesLogin("VLPL1234"))
        assertFalse(BrowseCursor.usesLogin("VLSEX"))
        assertFalse(BrowseCursor.usesLogin(""))
        assertFalse(BrowseCursor.usesLogin(null))
    }

    @Test
    fun cursorRoundTrip() {
        assertEquals("tok", BrowseCursor.wrap("tok", false))
        assertEquals("login:tok", BrowseCursor.wrap("tok", true))
        assertNull(BrowseCursor.wrap(null, true))
        assertEquals("tok" to false, BrowseCursor.unwrap("tok"))
        assertEquals("tok" to true, BrowseCursor.unwrap("login:tok"))
        assertEquals("a:b" to true, BrowseCursor.unwrap(BrowseCursor.wrap("a:b", true)!!))
    }

    @Test
    fun sourceErrorsCarryTheSourceId() {
        val disabled = sourceUnavailable(SourceId.ZING, SourceUnavailableException.Reason.DISABLED)
        assertEquals("source_disabled", disabled.code)
        assertEquals(JsonPrimitive("zing"), disabled.details?.get("sourceId"))
        assertEquals("source_degraded", sourceUnavailable(SourceId.NCT, SourceUnavailableException.Reason.DEGRADED).code)
        assertEquals("source_degraded", sourceUnavailable(SourceId.SC, SourceUnavailableException.Reason.NOT_REGISTERED).code)
    }
}
