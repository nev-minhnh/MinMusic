package com.metrolist.core.api

import com.metrolist.shared.model.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepDeadlineTest {
    @Test
    fun deadlineIsNowPlusTimeLeft() {
        assertEquals(1_000_000L + 900_000L, SleepDeadline.deadline(1_000_000L, 900_000L, null))
    }

    @Test
    fun noTimeLeftMeansNoDeadline() {
        assertNull(SleepDeadline.deadline(1_000_000L, 0L, null))
        assertNull(SleepDeadline.deadline(1_000_000L, -5L, null))
    }

    @Test
    fun theAnchorKeepsTheDeadlineStable() {
        val anchor = 5_000_000L
        assertEquals(anchor, SleepDeadline.deadline(1L, 10L, anchor))
        assertEquals(anchor, SleepDeadline.deadline(9_999_999L, 0L, anchor))
    }

    @Test
    fun seekLandsWhenTheAudioIsAtOrJustPastTheTarget() {
        assertTrue(SeekCheck.landed(60_000, 60_000, 200_000))
        assertTrue(SeekCheck.landed(60_000, 63_000, 200_000))
        assertTrue(SeekCheck.landed(60_000, 58_800, 200_000))
    }

    @Test
    fun seekDoesNotLandWhenThePositionStayedAway() {
        assertFalse(SeekCheck.landed(60_000, 5_000, 200_000))
        assertFalse(SeekCheck.landed(60_000, 120_000, 200_000))
        assertFalse(SeekCheck.landed(60_000, 0, 0))
    }

    @Test
    fun seekNearTheEndCountsAsLandedEvenAfterAReset() {
        assertTrue(SeekCheck.landed(199_000, 0, 200_000))
        assertTrue(SeekCheck.landed(500_000, 0, 200_000))
    }

    @Test
    fun aLostSeekToThreeSecondsBeforeTheEndIsNotLanded() {
        // W6: the auto-advance test seeks to dur - 3 s; a seek dropped during loading left the audio at 0
        assertFalse(SeekCheck.landed(269_221, 0, 272_221))
        // the contract test takes dur - 3 s from the metadata duration (273 s); the stream says 272.221 s
        assertFalse(SeekCheck.landed(270_000, 0, 272_221))
        assertTrue(SeekCheck.landed(269_221, 269_700, 272_221))
    }

    @Test
    fun autoplayWaitsOnlyAtTheEndWithRepeatOffAndAutoplayOn() {
        assertTrue(SeekCheck.autoplayLoads(1, 2, RepeatMode.OFF, true))
        assertFalse(SeekCheck.autoplayLoads(0, 2, RepeatMode.OFF, true))
        assertFalse(SeekCheck.autoplayLoads(1, 2, RepeatMode.ALL, true))
        assertFalse(SeekCheck.autoplayLoads(1, 2, RepeatMode.OFF, false))
        assertFalse(SeekCheck.autoplayLoads(-1, 0, RepeatMode.OFF, true))
    }
}
