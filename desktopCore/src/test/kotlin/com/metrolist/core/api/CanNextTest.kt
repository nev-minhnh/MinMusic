package com.metrolist.core.api

import com.metrolist.core.api.queue.canNext
import com.metrolist.shared.model.RepeatMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "only one song plays" cases: a one-track queue must still allow next (autoplay), unless autoplay cannot run. */
class CanNextTest {
    @Test
    fun laterEntryExists() {
        assertTrue(canNext(0, 2, RepeatMode.OFF, autoLoadMore = false, trackId = "yt1"))
        assertTrue(canNext(1, 5, RepeatMode.ONE, autoLoadMore = false, trackId = "yt1"))
    }

    @Test
    fun repeatAllWraps() {
        assertTrue(canNext(2, 3, RepeatMode.ALL, autoLoadMore = false, trackId = "local_1"))
    }

    @Test
    fun singleYoutubeTrackAutoplays() {
        assertTrue(canNext(0, 1, RepeatMode.OFF, autoLoadMore = true, trackId = "dQw4w9WgXcQ"))
    }

    @Test
    fun lastYoutubeTrackAutoplays() {
        assertTrue(canNext(9, 10, RepeatMode.OFF, autoLoadMore = true, trackId = "dQw4w9WgXcQ"))
    }

    @Test
    fun autoplayOffEndsTheQueue() {
        assertFalse(canNext(0, 1, RepeatMode.OFF, autoLoadMore = false, trackId = "dQw4w9WgXcQ"))
    }

    @Test
    fun repeatOneDoesNotAutoplay() {
        assertFalse(canNext(0, 1, RepeatMode.ONE, autoLoadMore = true, trackId = "dQw4w9WgXcQ"))
    }

    @Test
    fun otherSourcesCannotAutoplay() {
        for (id in listOf("sc:1", "zing:ZW1", "nct:abc", "local_9")) {
            assertFalse(canNext(0, 1, RepeatMode.OFF, autoLoadMore = true, trackId = id), id)
        }
    }

    @Test
    fun noTrackNoNext() {
        assertFalse(canNext(-1, 0, RepeatMode.OFF, autoLoadMore = true, trackId = null))
    }
}
