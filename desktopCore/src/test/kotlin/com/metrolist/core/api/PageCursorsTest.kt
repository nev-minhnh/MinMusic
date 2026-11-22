package com.metrolist.core.api

import com.metrolist.core.api.pages.PageCursorStore
import com.metrolist.core.server.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PageCursorsTest {
    private var now = 1_000L
    private fun store(
        capacity: Int = 64,
        ttlMs: Long = 600_000,
    ) = PageCursorStore(capacity, ttlMs) { now }

    @Test
    fun idsAreTwelveBase36Characters() {
        val id = store().put("x")
        assertEquals(12, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'z' }, id)
    }

    @Test
    fun idsAreUnique() {
        val s = store()
        val ids = (1..50).map { s.put(it) }
        assertEquals(50, ids.toSet().size)
    }

    @Test
    fun roundTrip() {
        val s = store()
        val id = s.put(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), s.get<List<Int>>(id))
    }

    @Test
    fun unknownIdIsRetryableStaleCursor() {
        val error = assertFailsWith<ApiException> { store().get<String>("nope") }
        assertEquals("bad_request", error.code)
        assertEquals("stale cursor", error.message)
        assertTrue(error.retryable)
    }

    @Test
    fun entryExpiresAfterTheTtl() {
        val s = store(ttlMs = 10 * 60 * 1000L)
        val id = s.put("page")
        now += 10 * 60 * 1000L - 1
        assertEquals("page", s.get<String>(id))
        now += 1
        assertFailsWith<ApiException> { s.get<String>(id) }
        assertEquals(0, s.size)
    }

    @Test
    fun expiredEntriesAreDroppedOnPut() {
        val s = store(ttlMs = 1_000)
        s.put("old")
        now += 2_000
        s.put("new")
        assertEquals(1, s.size)
    }

    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        val s = store(capacity = 3)
        val a = s.put("a")
        val b = s.put("b")
        val c = s.put("c")
        assertEquals("a", s.get<String>(a)) // a is now the most recently used
        val d = s.put("d") // evicts b
        assertEquals(3, s.size)
        assertFailsWith<ApiException> { s.get<String>(b) }
        assertEquals("a", s.get<String>(a))
        assertEquals("c", s.get<String>(c))
        assertEquals("d", s.get<String>(d))
    }
}
