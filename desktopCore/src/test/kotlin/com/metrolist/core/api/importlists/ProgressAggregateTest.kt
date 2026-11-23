package com.metrolist.core.api.importlists

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressAggregateTest {
    @Test
    fun sumsPlaylistsAndStaysBelowTotalUntilFinal() {
        val agg = ProgressAggregate(listOf(10, 5))
        agg.setTotal(0, 10)
        agg.setTotal(1, 5)
        agg.setDone(0, 10)
        agg.complete(0)
        agg.setDone(1, 5)
        val running = agg.snapshot(final = false)
        assertEquals(15, running.total)
        assertEquals(14, running.done)
        val final = agg.snapshot(final = true)
        assertEquals(15, final.done)
        assertEquals(15, final.total)
    }

    @Test
    fun doneNeverGoesDown() {
        val agg = ProgressAggregate(listOf(10))
        agg.setDone(0, 6)
        assertEquals(6, agg.snapshot(false).done)
        agg.setDone(0, 3)
        assertEquals(6, agg.snapshot(false).done)
    }

    @Test
    fun actualCountReplacesTheEstimate() {
        val agg = ProgressAggregate(listOf(0, 4))
        agg.setTotal(0, 7)
        assertEquals(11, agg.snapshot(false).total)
        agg.setTotal(1, 2)
        assertEquals(9, agg.snapshot(true).total)
    }

    @Test
    fun failedNeverExceedsDoneAndMatchesLabelsAtTheEnd() {
        val agg = ProgressAggregate(listOf(4))
        agg.setTotal(0, 4)
        agg.addFailed(listOf("a", "b"))
        assertEquals(0, agg.snapshot(false).failed)
        agg.complete(0)
        val final = agg.snapshot(true)
        assertEquals(2, final.failed)
        assertEquals(listOf("a", "b"), agg.failedTracks)
    }

    @Test
    fun loadFailureCountsAsOneFailedItem() {
        val agg = ProgressAggregate(listOf(0, 3))
        agg.markLoadFailed(0, "Broken list")
        agg.setTotal(1, 3)
        agg.complete(1)
        val final = agg.snapshot(true)
        assertEquals(4, final.total)
        assertEquals(4, final.done)
        assertEquals(1, final.failed)
        assertEquals(listOf("Broken list"), agg.failedTracks)
    }

    @Test
    fun emptyJobEndsAtZero() {
        val snap = ProgressAggregate(listOf(0)).snapshot(true)
        assertEquals(ProgressSnapshot(0, 0, 0), snap)
    }

    @Test
    fun throttlePassesFourPerSecondAndAlwaysTheFinalOne() {
        val t = ProgressThrottle(250)
        assertTrue(t.tryPass(1_000))
        assertFalse(t.tryPass(1_100))
        assertFalse(t.tryPass(1_249))
        assertTrue(t.tryPass(1_250))
        assertFalse(t.tryPass(1_300))
        assertTrue(t.tryPass(1_301, force = true))
    }

    @Test
    fun payloadShapes() {
        val running = progressPayload("imp-1", ProgressSnapshot(3, 10, 1)).jsonObject
        assertEquals("imp-1", running["jobId"]!!.jsonPrimitive.content)
        assertEquals(3, running["done"]!!.jsonPrimitive.content.toInt())
        assertNull(running["failedTracks"])
        assertNull(running["albumId"])
        val final = progressPayload("imp-1", ProgressSnapshot(10, 10, 1), listOf("A - B"), "lp:LPabc").jsonObject
        assertEquals("A - B", final["failedTracks"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("lp:LPabc", final["albumId"]!!.jsonPrimitive.content)
    }
}
