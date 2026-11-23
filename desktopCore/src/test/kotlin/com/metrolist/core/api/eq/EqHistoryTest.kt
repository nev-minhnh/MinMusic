package com.metrolist.core.api.eq

import com.metrolist.shared.equalizer.EqualizerProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqHistoryTest {
    private fun profile(preamp: Double) = EqualizerProfile(id = "p", name = "P", bands = emptyList(), preamp = preamp)

    @Test
    fun undoThenRedoWalksTheStack() {
        val history = EqHistory()
        history.push(profile(0.0))
        history.push(profile(1.0))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(1.0, history.undo(profile(2.0))?.preamp)
        assertTrue(history.canRedo)
        assertEquals(0.0, history.undo(profile(1.0))?.preamp)
        assertNull(history.undo(profile(0.0)))
        assertEquals(1.0, history.redo(profile(0.0))?.preamp)
        assertEquals(2.0, history.redo(profile(1.0))?.preamp)
        assertNull(history.redo(profile(2.0)))
    }

    @Test
    fun aNewEditClearsRedo() {
        val history = EqHistory()
        history.push(profile(0.0))
        history.undo(profile(1.0))
        assertTrue(history.canRedo)
        history.push(profile(0.0))
        assertFalse(history.canRedo)
    }

    @Test
    fun capsAtFiftyDroppingTheOldest() {
        val history = EqHistory()
        for (i in 0 until 60) history.push(profile(i.toDouble()))
        var count = 0
        var last = profile(99.0)
        while (true) {
            val previous = history.undo(last) ?: break
            last = previous
            count++
        }
        assertEquals(50, count)
        assertEquals(10.0, last.preamp)
    }

    @Test
    fun sameKeyWithinTheWindowIsOneStep() {
        val history = EqHistory(coalesceMs = 400)
        history.push(profile(0.0), "band:1", 1000)
        history.push(profile(1.0), "band:1", 1016)
        history.push(profile(2.0), "band:1", 1032)
        assertEquals(0.0, history.undo(profile(3.0))?.preamp)
        assertFalse(history.canUndo)
    }

    @Test
    fun differentKeyOrLaterPushIsANewStep() {
        val history = EqHistory(coalesceMs = 400)
        history.push(profile(0.0), "band:1", 1000)
        history.push(profile(1.0), "band:2", 1010)
        history.push(profile(2.0), "band:2", 2000)
        assertEquals(2.0, history.undo(profile(3.0))?.preamp)
        assertEquals(1.0, history.undo(profile(2.0))?.preamp)
        assertEquals(0.0, history.undo(profile(1.0))?.preamp)
    }

    @Test
    fun undoEndsCoalescingAndClearEmptiesBoth() {
        val history = EqHistory(coalesceMs = 400)
        history.push(profile(0.0), "band:1", 1000)
        history.undo(profile(1.0))
        history.push(profile(0.0), "band:1", 1050)
        assertTrue(history.canUndo)
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
