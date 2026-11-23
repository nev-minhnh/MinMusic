package com.metrolist.core.api.download

import com.metrolist.desktop.storage.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressThrottleTest {
    private fun ev(
        id: String,
        p: Double,
        state: String = "downloading",
    ) = ProgressEvent(id, state, p)

    @Test
    fun firstEventPassesAtOnce() {
        val t = ProgressThrottle()
        t.submit(ev("a", 0.1))
        assertEquals(ev("a", 0.1), t.poll(1_000))
        assertNull(t.poll(1_000))
    }

    @Test
    fun keepsOnlyTheLatestValuePerId() {
        val t = ProgressThrottle()
        t.submit(ev("a", 0.1))
        t.poll(0)
        t.submit(ev("a", 0.2))
        t.submit(ev("a", 0.3))
        assertNull(t.poll(100))
        assertEquals(0.3, t.poll(500)!!.progress)
    }

    @Test
    fun perIdGapIsHalfASecond() {
        val t = ProgressThrottle()
        t.submit(ev("a", 0.1))
        t.poll(0)
        t.submit(ev("a", 0.2))
        assertNull(t.poll(300))
        assertNull(t.poll(499))
        assertEquals(0.2, t.poll(500)!!.progress)
    }

    @Test
    fun totalGapIsAQuarterSecondAcrossIds() {
        val t = ProgressThrottle()
        t.submit(ev("a", 0.1))
        t.submit(ev("b", 0.1))
        assertEquals("a", t.poll(0)!!.id)
        assertNull(t.poll(100))
        assertEquals("b", t.poll(250)!!.id)
    }

    @Test
    fun aStateChangeSkipsThePerIdGapButNotTheTotalGap() {
        val t = ProgressThrottle()
        t.submit(ev("a", 0.5))
        t.poll(0)
        t.submit(ev("a", 1.0, "downloaded"))
        assertNull(t.poll(100))
        assertEquals("downloaded", t.poll(250)!!.state)
    }

    @Test
    fun stateChangesGoBeforeProgressTicks() {
        val t = ProgressThrottle()
        t.submit(ev("a", 0.1))
        t.poll(0)
        t.submit(ev("a", 0.2))
        t.submit(ev("b", 0.0, "queued"))
        assertEquals("b", t.poll(600)!!.id)
    }

    @Test
    fun hasPendingTracksTheBacklog() {
        val t = ProgressThrottle()
        assertTrue(!t.hasPending())
        t.submit(ev("a", 0.1))
        assertTrue(t.hasPending())
        t.poll(0)
        assertTrue(!t.hasPending())
    }

    @Test
    fun floodStaysUnderFourHertzTotalAndTwoHertzPerId() {
        val t = ProgressThrottle()
        val sent = ArrayList<ProgressEvent>()
        var now = 0L
        // five downloads report every 10 ms for 10 s; the poller runs every 10 ms
        while (now <= 10_000) {
            for (id in listOf("a", "b", "c", "d", "e")) t.submit(ev(id, now / 10_000.0))
            t.poll(now)?.let { sent += it }
            now += 10
        }
        assertTrue(sent.size <= 4 * 10 + 1, "total ${sent.size}")
        for (id in listOf("a", "b", "c", "d", "e")) {
            val n = sent.count { it.id == id }
            assertTrue(n <= 2 * 10 + 1, "$id sent $n")
        }
        assertEquals(5, sent.map { it.id }.toSet().size, "every id is served")
    }

    @Test
    fun terminalStateForgetsTheId() {
        val t = ProgressThrottle()
        t.submit(ev("a", 0.5))
        t.poll(0)
        t.submit(ev("a", 1.0, "downloaded"))
        t.poll(250)
        // a re-download starts later: the first event of the new run passes without waiting
        t.submit(ev("a", 0.0, "queued"))
        assertEquals("queued", t.poll(500)!!.state)
    }
}

class ProgressDiffTest {
    @Test
    fun reportsChangedStatesAndProgressAndVanishedIds() {
        val prevS = mapOf("a" to DownloadState.QUEUED, "b" to DownloadState.DOWNLOADING, "c" to DownloadState.DOWNLOADED)
        val prevP = mapOf("b" to 0.2f)
        val s = mapOf("a" to DownloadState.DOWNLOADING, "b" to DownloadState.DOWNLOADING, "c" to DownloadState.DOWNLOADED)
        val p = mapOf("a" to 0.0f, "b" to 0.4f)
        val events = ProgressDiff.events(prevS, prevP, s, p)
        assertEquals(setOf("a", "b"), events.map { it.id }.toSet())
        assertEquals(0.4, events.first { it.id == "b" }.progress, 1e-6)
        val gone = ProgressDiff.events(prevS, prevP, mapOf("c" to DownloadState.DOWNLOADED), emptyMap())
        assertEquals(setOf("a", "b"), gone.filter { it.state == "none" }.map { it.id }.toSet())
    }

    @Test
    fun finishedWithoutProgressReportsOne() {
        val e = ProgressDiff.events(mapOf("a" to DownloadState.DOWNLOADING), mapOf("a" to 0.9f), mapOf("a" to DownloadState.DOWNLOADED), emptyMap())
        assertEquals(ProgressEvent("a", "downloaded", 1.0), e.single())
    }

    @Test
    fun unchangedIsSilent() {
        val s = mapOf("a" to DownloadState.DOWNLOADING)
        val p = mapOf("a" to 0.5f)
        assertEquals(emptyList(), ProgressDiff.events(s, p, s, p))
    }

    @Test
    fun stateWireNames() {
        assertEquals("none", DownloadState.NOT_DOWNLOADED.wire())
        assertEquals("failed", DownloadState.FAILED.wire())
    }
}
