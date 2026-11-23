package com.metrolist.core.api.queue

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.MusicQueueState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueIndexTest {
    private fun song(id: String) = SongItem(id = id, title = "T $id", thumbnail = null, artists = listOf(ArtistTiny("a1", "Artist")), album = null, duration = 200)

    /** Keys 10, 11, 12... so qid text and index differ. */
    private fun state(
        ids: List<String>,
        current: Int,
    ) = MusicQueueState(ids.mapIndexed { i, id -> MusicQueueEntry(10L + i, song(id)) }, current)

    @Test
    fun qidIsTheKeyAsText() {
        val s = state(listOf("a", "b", "c"), 1)
        assertEquals(0, QueueIndex.indexOfQid(s, "10"))
        assertEquals(2, QueueIndex.indexOfQid(s, "12"))
        assertEquals(-1, QueueIndex.indexOfQid(s, "2"))
        assertEquals(-1, QueueIndex.indexOfQid(s, "q-unknown"))
    }

    @Test
    fun removeIgnoresUnknownQids() {
        val s = state(listOf("a", "b", "c", "d"), 2)
        assertEquals(setOf(0, 3), QueueIndex.indicesOf(s, listOf("10", "13", "999", "nope")))
        assertEquals(emptySet(), QueueIndex.indicesOf(s, listOf("999")))
        assertEquals(setOf(1), QueueIndex.indicesOf(s, listOf("11", "11")))
    }

    @Test
    fun clearKeepingTheCurrentEntryRemovesEverythingElse() {
        assertEquals(setOf(0, 1, 2, 4, 5), QueueIndex.clearIndices(6, 3, keepCurrent = true))
        assertEquals(emptySet(), QueueIndex.clearIndices(1, 0, keepCurrent = true))
        assertEquals(setOf(1, 2), QueueIndex.clearIndices(3, 0, keepCurrent = true))
    }

    @Test
    fun clearWithoutKeepingRemovesEverything() {
        assertEquals(setOf(0, 1, 2), QueueIndex.clearIndices(3, 1, keepCurrent = false))
        assertEquals(emptySet(), QueueIndex.clearIndices(0, -1, keepCurrent = false))
    }

    @Test
    fun moveNeedsTwoValidSlots() {
        assertTrue(QueueIndex.validMove(6, 4, 2))
        assertTrue(QueueIndex.validMove(6, 0, 5))
        assertFalse(QueueIndex.validMove(6, 6, 0))
        assertFalse(QueueIndex.validMove(6, 0, -1))
        assertFalse(QueueIndex.validMove(0, 0, 0))
    }

    @Test
    fun addSlotIsClampedToTheOldEnd() {
        assertEquals(0, QueueIndex.addSlot(0, 5))
        assertEquals(3, QueueIndex.addSlot(3, 5))
        assertEquals(5, QueueIndex.addSlot(99, 5))
        assertEquals(0, QueueIndex.addSlot(-4, 5))
    }

    @Test
    fun radioIsReadyWhenFullAndTheSeedPlays() {
        val ids = listOf("seed") + (1..11).map { "r$it" }
        assertTrue(QueueIndex.radioReady(state(ids, 0), "seed", 10))
        assertFalse(QueueIndex.radioReady(state(ids.take(5), 0), "seed", 10))
        assertFalse(QueueIndex.radioReady(state(ids, 1), "seed", 10))
        assertFalse(QueueIndex.radioReady(MusicQueueState(), "seed", 1))
    }
}
