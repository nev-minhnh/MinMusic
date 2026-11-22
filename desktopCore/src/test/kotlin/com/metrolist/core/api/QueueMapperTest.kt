package com.metrolist.core.api

import com.metrolist.core.api.queue.QueueMapper
import com.metrolist.core.api.queue.QueuePosition
import com.metrolist.core.api.queue.QueueRevisionCounter
import com.metrolist.core.server.ApiException
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.MusicQueueState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueMapperTest {
    private fun song(id: String) = SongItem(id = id, title = "T $id", thumbnail = null, artists = listOf(ArtistTiny("a1", "Artist")), album = null, duration = 200)

    private fun state(
        count: Int,
        current: Int,
        firstKey: Long = 100,
    ) = MusicQueueState(List(count) { MusicQueueEntry(firstKey + it, song("s$it")) }, current)

    private fun JsonObject.ids(field: String) = getValue(field).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun windowSlicesAndAddsQid() {
        val result = QueueMapper.window(state(10, 3), revision = 7, offset = 2, limit = 4)
        assertEquals(10, result.getValue("total").jsonPrimitive.int)
        assertEquals(7, result.getValue("revision").jsonPrimitive.int)
        assertEquals(3, result.getValue("currentIndex").jsonPrimitive.int)
        assertEquals(listOf("s2", "s3", "s4", "s5"), result.ids("items"))
        assertEquals("102", result.getValue("items").jsonArray[0].jsonObject.getValue("qid").jsonPrimitive.content)
    }

    @Test
    fun windowClampsOffsetAndLimit() {
        val big = state(500, 0)
        assertEquals(200, QueueMapper.window(big, 1, 0, 100_000).getValue("items").jsonArray.size)
        assertEquals(1, QueueMapper.window(big, 1, 0, 0).getValue("items").jsonArray.size)
        assertEquals(0, QueueMapper.window(big, 1, 900, 50).getValue("items").jsonArray.size)
        assertEquals(10, QueueMapper.window(big, 1, 490, 50).getValue("items").jsonArray.size)
        assertEquals(0, QueueMapper.window(MusicQueueState(), 1, 0, 50).getValue("items").jsonArray.size)
    }

    @Test
    fun snapshotHeadStartsFiveBeforeCurrent() {
        val result = QueueMapper.snapshot(state(100, 20), revision = 1)
        val head = result.ids("head")
        assertEquals(50, head.size)
        assertEquals("s15", head.first())
        assertEquals("s64", head.last())
    }

    @Test
    fun snapshotNearTheStartAndOnAnEmptyQueue() {
        assertEquals("s0", QueueMapper.snapshot(state(100, 2), 1).ids("head").first())
        assertEquals(1, QueueMapper.snapshot(state(1, 0), 1).ids("head").size)
        val empty = QueueMapper.snapshot(MusicQueueState(), 0)
        assertEquals(JsonArray(emptyList()), empty.getValue("head"))
        assertEquals(-1, empty.getValue("currentIndex").jsonPrimitive.int)
    }

    @Test
    fun parsePosition() {
        assertEquals(QueuePosition.Next, QueueMapper.parsePosition(JsonPrimitive("next")))
        assertEquals(QueuePosition.End, QueueMapper.parsePosition(JsonPrimitive("end")))
        assertEquals(QueuePosition.At(3), QueueMapper.parsePosition(JsonPrimitive(3)))
        assertEquals(QueuePosition.At(0), QueueMapper.parsePosition(JsonPrimitive("0")))
        for (bad in listOf<JsonPrimitive?>(null, JsonPrimitive("soon"), JsonPrimitive(-1), JsonPrimitive(true))) {
            assertFailsWith<ApiException>("$bad") { QueueMapper.parsePosition(bad) }
        }
    }

    @Test
    fun insertIndexNeverGoesBeforeOrAtTheCurrentEntry() {
        assertEquals(5, QueueMapper.insertIndex(requested = 5, currentIndexAfter = 1, sizeBefore = 10))
        assertEquals(2, QueueMapper.insertIndex(requested = 0, currentIndexAfter = 1, sizeBefore = 10))
        assertEquals(10, QueueMapper.insertIndex(requested = 99, currentIndexAfter = 1, sizeBefore = 10))
        // An empty queue that just started playing the added songs: nothing to move before.
        assertEquals(1, QueueMapper.insertIndex(requested = 0, currentIndexAfter = 0, sizeBefore = 0))
    }

    @Test
    fun addedKeysAreTheNewEntriesInOrder() {
        val before = state(3, 0)
        val after = MusicQueueState(before.entries + listOf(MusicQueueEntry(900, song("x")), MusicQueueEntry(901, song("y"))), 0)
        assertEquals(listOf(900L, 901L), QueueMapper.addedKeys(before, after))
        assertEquals(emptyList(), QueueMapper.addedKeys(before, before))
    }

    @Test
    fun atEndOnlyOnTheLastEntry() {
        assertTrue(QueueMapper.atEnd(state(3, 2)))
        assertTrue(QueueMapper.atEnd(state(1, 0)))
        assertFalse(QueueMapper.atEnd(state(3, 1)))
        assertFalse(QueueMapper.atEnd(MusicQueueState()))
    }

    @Test
    fun revisionBumpsOnlyOnDistinctStates() {
        val counter = QueueRevisionCounter()
        val a = state(3, 0)
        assertEquals(1L, counter.observe(a))
        assertEquals(1L, counter.observe(a))
        assertEquals(1L, counter.observe(state(3, 0))) // equal copy
        assertEquals(2L, counter.observe(state(3, 1))) // index moved
        assertEquals(3L, counter.observe(state(4, 1))) // entry added
        assertEquals(3L, counter.observe(state(4, 1)))
    }
}
