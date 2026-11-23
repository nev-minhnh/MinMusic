package com.metrolist.core.api.library

import com.metrolist.core.server.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryChangesTest {
    private fun change(
        revision: Long,
        vararg lists: String,
        userAlbums: Boolean = false,
    ) = LibraryChange(lists.toSet(), revision, userAlbums)

    // --- coalescing (<= 4 Hz) --------------------------------------------------------------------------------------

    @Test
    fun firstChangeGoesOutAtOnce() {
        val coalescer = ChangeCoalescer(250)
        assertFalse(coalescer.hasPending())
        coalescer.add(change(2, "liked"))
        assertEquals(0, coalescer.waitMs(1_000))
        val out = assertNotNull(coalescer.flush(1_000))
        assertEquals(listOf("liked"), out.lists)
        assertEquals(2, out.revision)
        assertFalse(out.userAlbums)
        assertFalse(coalescer.hasPending())
    }

    @Test
    fun laterChangesWaitForTheIntervalAndMerge() {
        val coalescer = ChangeCoalescer(250)
        coalescer.add(change(2, "liked"))
        coalescer.flush(1_000)
        coalescer.add(change(3, "songs"))
        assertEquals(250, coalescer.waitMs(1_000))
        coalescer.add(change(4, "liked", "albums", userAlbums = true))
        assertEquals(100, coalescer.waitMs(1_150))
        val out = assertNotNull(coalescer.flush(1_250))
        assertEquals(listOf("songs", "liked", "albums"), out.lists)
        assertEquals(4, out.revision)
        assertTrue(out.userAlbums)
        assertNull(coalescer.flush(1_300))
        assertEquals(0, coalescer.waitMs(1_300))
    }

    @Test
    fun aBurstNeverExceedsFourEmissionsPerSecond() {
        val coalescer = ChangeCoalescer(250)
        var now = 0L
        var emissions = 0
        var revision = 1L
        // 100 changes, one every 10 ms, for one second; the topic loop waits waitMs() before it flushes.
        repeat(100) {
            coalescer.add(change(++revision, "songs"))
            if (coalescer.waitMs(now) == 0L) {
                coalescer.flush(now)?.let { emissions++ }
            }
            now += 10
        }
        assertTrue(emissions in 1..4, "emissions: $emissions")
        // what is still pending goes out as one more emission once the interval has passed
        val rest = assertNotNull(coalescer.flush(now + 250))
        assertEquals(revision, rest.revision)
    }

    // --- album revision log (userAlbum.move conflicts) -------------------------------------------------------------

    @Test
    fun staleWhenTheAlbumChangedSinceTheWindow() {
        val log = AlbumRevisionLog()
        val before = AlbumRevisionLog.signature(listOf(1, 2, 3))
        val after = AlbumRevisionLog.signature(listOf(2, 3, 1))
        log.record("lp:a", 10, before)
        assertFalse(log.isStale("lp:a", 10, 10, before))
        assertTrue(log.isStale("lp:a", 10, 12, after))
    }

    @Test
    fun aGlobalRevisionBumpWithoutAnAlbumChangeIsNotAConflict() {
        val log = AlbumRevisionLog()
        val signature = AlbumRevisionLog.signature(listOf(1, 2, 3))
        log.record("lp:a", 10, signature)
        assertFalse(log.isStale("lp:a", 10, 15, signature))
    }

    @Test
    fun withoutAWindowRecordTheRevisionMustBeCurrent() {
        val log = AlbumRevisionLog()
        val signature = AlbumRevisionLog.signature(listOf(1))
        assertFalse(log.isStale("lp:x", 7, 7, signature))
        assertTrue(log.isStale("lp:x", 7, 8, signature))
    }

    @Test
    fun signatureDependsOnOrderAndContent() {
        assertEquals(AlbumRevisionLog.signature(listOf(1, 2)), AlbumRevisionLog.signature(listOf(1, 2)))
        assertTrue(AlbumRevisionLog.signature(listOf(1, 2)) != AlbumRevisionLog.signature(listOf(2, 1)))
        assertTrue(AlbumRevisionLog.signature(listOf(1, 2)) != AlbumRevisionLog.signature(listOf(1, 2, 3)))
    }

    @Test
    fun logForgetsTheOldestEntriesPastItsCapacity() {
        val log = AlbumRevisionLog(capacity = 2)
        log.record("a", 1, 11)
        log.record("a", 2, 12)
        log.record("a", 3, 13)
        // revision 1 was evicted: it falls back to the plain revision comparison
        assertTrue(log.isStale("a", 1, 3, 11))
        assertFalse(log.isStale("a", 3, 3, 13))
    }

    // --- user album move arithmetic --------------------------------------------------------------------------------

    @Test
    fun moveTranslatesWindowIndicesToEntryIds() {
        val entries = listOf(41, 42, 43, 44)
        assertEquals(41 to 43, UserAlbumMath.translate(entries, 0, 2))
        assertEquals(44 to 41, UserAlbumMath.translate(entries, 3, 0))
        assertNull(UserAlbumMath.translate(entries, 1, 1))
    }

    @Test
    fun moveOutsideTheAlbumIsBadRequest() {
        val entries = listOf(41, 42)
        assertEquals("bad_request", assertFailsWith<ApiException> { UserAlbumMath.translate(entries, 0, 2) }.code)
        assertEquals("bad_request", assertFailsWith<ApiException> { UserAlbumMath.translate(entries, -1, 0) }.code)
        assertEquals("bad_request", assertFailsWith<ApiException> { UserAlbumMath.translate(emptyList(), 0, 0) }.code)
    }

    @Test
    fun revisionBumpsAreMonotonicAndAnnounced() {
        val before = LibraryChanges.revision
        val one = LibraryChanges.bump(listOf("songs"))
        val two = LibraryChanges.bump(listOf("liked"), userAlbums = true)
        assertTrue(before < one && one < two)
        assertEquals(two, LibraryChanges.revision)
    }
}
