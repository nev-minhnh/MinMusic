package com.metrolist.core.api.eq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrumQuantiseTest {
    private val centers = listOf(45.0, 63.0, 90.0, 125.0, 180.0, 250.0, 355.0, 500.0, 710.0, 1000.0, 1400.0, 2000.0, 2800.0, 4000.0, 5600.0, 8000.0, 11000.0, 15000.0)

    private fun u(b: Byte) = b.toInt() and 0xFF

    @Test
    fun levelMapsZeroToOneOntoByteRange() {
        assertEquals(0, u(SpectrumQuantise.levelToByte(0f)))
        assertEquals(255, u(SpectrumQuantise.levelToByte(1f)))
        assertEquals(128, u(SpectrumQuantise.levelToByte(0.5f)))
        assertEquals(0, u(SpectrumQuantise.levelToByte(-3f)))
        assertEquals(255, u(SpectrumQuantise.levelToByte(7f)))
    }

    @Test
    fun alwaysThirtyTwoBytes() {
        assertEquals(32, SpectrumQuantise.toBytes(FloatArray(centers.size), centers).size)
    }

    @Test
    fun silenceIsZeroAndFullScaleIsAll255() {
        assertTrue(SpectrumQuantise.toBytes(FloatArray(centers.size), centers).all { it.toInt() == 0 })
        assertTrue(SpectrumQuantise.toBytes(FloatArray(centers.size) { 1f }, centers).all { u(it) == 255 })
    }

    @Test
    fun edgesKeepTheEndLevelsAndTheRampIsMonotonic() {
        val levels = FloatArray(centers.size) { if (it == 0) 0f else 1f }
        val out = SpectrumQuantise.toBytes(levels, centers)
        assertEquals(0, u(out.first()))
        assertEquals(255, u(out.last()))
        assertTrue((1 until out.size).all { u(out[it]) >= u(out[it - 1]) })
    }

    @Test
    fun aSingleLoudBandStaysNearItsFrequency() {
        val levels = FloatArray(centers.size).also { it[9] = 1f } // 1 kHz
        val out = SpectrumQuantise.toBytes(levels, centers)
        val peak = out.indices.maxBy { u(out[it]) }
        // output band j sits at 45 * (15000 / 45) ^ (j / 31), so 1 kHz is at about j = 16.5
        assertTrue(peak in 15..18, "peak at $peak")
        assertTrue(u(out[peak]) >= 150)
    }
}
