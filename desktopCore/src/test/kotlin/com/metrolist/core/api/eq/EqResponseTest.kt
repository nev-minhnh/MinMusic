package com.metrolist.core.api.eq

import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.ParametricEqBand
import com.metrolist.shared.equalizer.createEqualizerBands
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqResponseTest {
    private fun flat() = EqualizerProfile(id = "f", name = "Flat", bands = createEqualizerBands(10))

    @Test
    fun gridIsLogSpacedFrom20HzTo20kHzAt48k() {
        val hz = EqResponse.grid(256)
        assertEquals(256, hz.size)
        assertEquals(20.0, hz.first(), 1e-9)
        assertEquals(20_000.0, hz.last(), 1e-6)
        assertTrue((1 until hz.size).all { hz[it] > hz[it - 1] })
        val ratio = hz[1] / hz[0]
        assertTrue((1 until hz.size).all { abs(hz[it] / hz[it - 1] - ratio) < 1e-9 })
    }

    @Test
    fun gridStopsBelowNyquistAtLowRates() {
        val hz = EqResponse.grid(64, rate = 32_000)
        assertEquals(32_000 * 0.475, hz.last(), 1e-6)
    }

    @Test
    fun flatProfileIsZeroDb() {
        val (_, db) = EqResponse.compute(flat(), 256)
        assertTrue(db.all { abs(it) < 0.01 })
        assertTrue(EqResponse.compute(null, 16).second.all { abs(it) < 0.01 })
    }

    @Test
    fun sixDbPeakAtOneKhzPeaksAtAboutSixDb() {
        val profile = flat().copy(bands = listOf(ParametricEqBand(1000.0, 6.0, 1.41)))
        val (hz, db) = EqResponse.compute(profile, 256)
        val peak = db.indices.maxBy { db[it] }
        assertTrue(abs(hz[peak] - 1000.0) < 40.0, "peak at ${hz[peak]}")
        assertEquals(6.0, db[peak], 0.1)
        assertTrue(abs(db.first()) < 0.5 && abs(db.last()) < 0.5)
    }

    @Test
    fun outputGainIsAddedAndPreampIsIncluded() {
        val profile = flat().copy(preamp = -3.0, outputGainDb = 2.0)
        val (_, db) = EqResponse.compute(profile, 32)
        assertTrue(db.all { abs(it - (-1.0)) < 0.01 })
    }
}
