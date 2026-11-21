package com.metrolist.shared.equalizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParametricEqualizerTest {
    @Test
    fun disabledEqualizerPassesFullScaleAudioUntouched() {
        val equalizer = ParametricEqualizer(48_000, 2)
        equalizer.update(null)
        val input = ShortArray(960) { if (it % 2 == 0) 32767 else -32768 }
        val output = input.copyOf()
        equalizer.processPcm16(output)
        assertContentEquals(input, output)
    }

    @Test
    fun lookaheadLimiterKeepsBoostedPeaksBelowCeiling() {
        val equalizer = ParametricEqualizer(48_000, 2)
        equalizer.update(EqualizerProfile("test", "test", listOf(ParametricEqBand(1_000.0, 6.0, 1.0))))
        val ceiling = 32768 * 0.9661
        var frame = 0
        repeat(50) {
            val chunk =
                ShortArray(960) { index ->
                    (sin(2 * PI * 1_000.0 * (frame + index / 2) / 48_000) * 32_000).roundToInt().toShort()
                }
            frame += chunk.size / 2
            equalizer.processPcm16(chunk, outputGainDb = 12.0)
            assertTrue(chunk.all { abs(it.toInt()) <= ceiling }, "peak above -0.3 dBFS")
        }
        assertTrue(equalizer.gainReductionDb > 15.0)
    }

    @Test
    fun importClampsPreampToProfileRange() {
        val (preamp, bands) = assertNotNull(parseParametricEq("Preamp: -40 dB\nFilter 1: ON PK Fc 100 Hz Gain 3 dB Q 1.0"))
        assertEquals(MIN_PROFILE_PREAMP_DB, preamp)
        assertEquals(1, bands.size)
    }
}
