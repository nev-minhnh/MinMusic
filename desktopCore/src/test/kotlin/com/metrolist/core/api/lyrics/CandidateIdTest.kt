package com.metrolist.core.api.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CandidateIdTest {
    @Test
    fun formatIsProviderColonIndex() {
        assertEquals("LRCLIB:0", CandidateId.format("LRCLIB", 0))
        assertEquals("KUGOU:12", CandidateId.format("KUGOU", 12))
    }

    @Test
    fun parseRoundTrips() {
        assertEquals(CandidateRef("BETTERLYRICS", 3), CandidateId.parse(CandidateId.format("BETTERLYRICS", 3)))
    }

    @Test
    fun parseRejectsMalformedIds() {
        assertNull(CandidateId.parse(""))
        assertNull(CandidateId.parse("LRCLIB"))
        assertNull(CandidateId.parse(":1"))
        assertNull(CandidateId.parse("LRCLIB:"))
        assertNull(CandidateId.parse("LRCLIB:x"))
        assertNull(CandidateId.parse("LRCLIB:-1"))
    }

    @Test
    fun providerOrderValidation() {
        val all = CandidateId.configurable
        assertEquals(all, CandidateId.validOrder(all))
        assertEquals(listOf(all[1], all[0]), CandidateId.validOrder(listOf(" ${all[1]} ", all[0])))
        assertNull(CandidateId.validOrder(listOf("NOPE")))
        assertNull(CandidateId.validOrder(listOf(all[0], all[0])))
        assertNull(CandidateId.validOrder(listOf("YOUTUBE")))
    }
}
