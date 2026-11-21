/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val MIN_AUDIO_PREAMP_DB = -12.0
const val MAX_AUDIO_BOOST_DB = 12.0
const val MIN_PROFILE_PREAMP_DB = -24.0
const val MAX_PROFILE_PREAMP_DB = 24.0

/** RBJ biquad chain; use one instance per audio pipeline because filter state is not thread-safe. */
class ParametricEqualizer(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private var profile: EqualizerProfile? = null

    // Per filter: b0, b1, b2, a1, a2 (normalized by a0).
    private var coefficients = emptyArray<DoubleArray>()
    private var appliedBands = emptyList<ParametricEqBand>()

    init {
        require(sampleRate > 0 && channelCount > 0)
    }

    private var activeFilters = IntArray(0)

    // Per filter: x1, x2, y1, y2 for each channel.
    private var state = emptyArray<DoubleArray>()
    private var preampGain = 1.0

    // Lookahead limiter; while it runs, the output is delayed by lookaheadFrames - 1 frames.
    private val lookaheadFrames = maxOf(1, (sampleRate * LIMITER_LOOKAHEAD_SECONDS).roundToInt())
    private val delayFrames = lookaheadFrames - 1
    private val delayLine = DoubleArray(maxOf(1, delayFrames * channelCount))
    private var delayPosition = 0
    private val minimumValues = DoubleArray(lookaheadFrames)
    private val minimumFrames = LongArray(lookaheadFrames)
    private var minimumHead = 0
    private var minimumSize = 0
    private val averageWindow = DoubleArray(lookaheadFrames) { 1.0 }
    private var averagePosition = 0
    private var averageSum = lookaheadFrames.toDouble()
    private var releaseEnvelope = 1.0
    private var frameCounter = 0L
    private var limiterActive = false
    private val releaseDecay = exp(-1.0 / (sampleRate * LIMITER_RELEASE_SECONDS))
    var gainReductionDb = 0.0
        private set
    private var processingBuffer = DoubleArray((sampleRate / 10 * channelCount).coerceAtLeast(1))

    fun update(profile: EqualizerProfile?): Boolean {
        if (profile !== this.profile) {
            this.profile = profile
            val bands =
                profile?.bands.orEmpty().filter {
                    it.enabled && it.frequency > 0.0 && it.frequency < sampleRate / 2.0 &&
                        it.gain.isFinite() && it.q.isFinite() && it.q > 0.0
                }
            val previousBands = appliedBands
            val previousCoefficients = coefficients
            val sameTopology =
                bands.size == appliedBands.size &&
                    bands.indices.all { index ->
                        val before = appliedBands[index]
                        val after = bands[index]
                        before.filterType == after.filterType
                    }
            val active = bands.indices.filter { !bands[it].isIdentity() }.toIntArray()
            if (!sameTopology) {
                state = Array(bands.size) { DoubleArray(channelCount * 4) }
            } else {
                // A skipped band's state stopped advancing; restart it rather than replay stale history.
                for (index in active) if (index !in activeFilters) state[index].fill(0.0)
            }
            activeFilters = active
            coefficients =
                bands
                    .mapIndexed { index, band ->
                        if (previousBands.getOrNull(index) == band) previousCoefficients[index] else coefficientsFor(band)
                    }.toTypedArray()
            appliedBands = bands
            val preampDb = profile?.preamp?.takeIf { it.isFinite() } ?: 0.0
            preampGain = 10.0.pow(preampDb.coerceIn(MIN_PROFILE_PREAMP_DB, MAX_PROFILE_PREAMP_DB) / 20.0)
        }
        return profile != null
    }

    fun process(
        sample: Double,
        channel: Int,
    ): Double {
        var x = sample
        for (i in activeFilters) {
            val c = coefficients[i]
            val s = state[i]
            val o = channel * 4
            val y = c[0] * x + c[1] * s[o] + c[2] * s[o + 1] - c[3] * s[o + 2] - c[4] * s[o + 3]
            s[o + 1] = s[o]
            s[o] = x
            s[o + 3] = s[o + 2]
            s[o + 2] = y
            x = y
        }
        return x * preampGain
    }

    fun responseDb(frequency: Double): Double {
        require(frequency in 0.0..sampleRate / 2.0)
        val w = 2.0 * PI * frequency / sampleRate
        var db = 20.0 * log10(preampGain)
        for (c in coefficients) {
            val realNumerator = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
            val imaginaryNumerator = c[1] * sin(w) + c[2] * sin(2 * w)
            val realDenominator = 1.0 + c[3] * cos(w) + c[4] * cos(2 * w)
            val imaginaryDenominator = c[3] * sin(w) + c[4] * sin(2 * w)
            db += 10.0 *
                log10(
                    (realNumerator * realNumerator + imaginaryNumerator * imaginaryNumerator) /
                        (realDenominator * realDenominator + imaginaryDenominator * imaginaryDenominator),
                )
        }
        return db
    }

    fun processPcm16(
        samples: ShortArray,
        count: Int = samples.size,
        outputGainDb: Double = 0.0,
        limiter: Boolean = true,
    ) {
        val outputGain = 10.0.pow(outputGainDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB) / 20.0)
        if (isBypassed(outputGain)) return
        if (processingBuffer.size < count) processingBuffer = DoubleArray(count)
        for (i in 0 until count) processingBuffer[i] = samples[i] / 32768.0
        processBuffer(count, outputGain, limiter)
        for (i in 0 until count) {
            val y = processingBuffer[i] * 32768.0
            samples[i] = y.coerceIn(-32768.0, 32767.0).roundToInt().toShort()
        }
    }

    fun processPcmFloat(
        samples: FloatArray,
        count: Int = samples.size,
        outputGainDb: Double = 0.0,
        limiter: Boolean = true,
    ) {
        val outputGain = 10.0.pow(outputGainDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB) / 20.0)
        if (isBypassed(outputGain)) return
        if (processingBuffer.size < count) processingBuffer = DoubleArray(count)
        for (i in 0 until count) processingBuffer[i] = samples[i].toDouble()
        processBuffer(count, outputGain, limiter)
        for (i in 0 until count) samples[i] = processingBuffer[i].coerceIn(-1.0, 1.0).toFloat()
    }

    // Without filters or gain the output must stay bit-identical to the input, so neither the limiter nor its delay runs.
    private fun isBypassed(outputGain: Double): Boolean {
        if (activeFilters.isNotEmpty() || preampGain != 1.0 || outputGain != 1.0) return false
        limiterActive = false
        gainReductionDb = 0.0
        return true
    }

    private fun processBuffer(
        count: Int,
        outputGain: Double,
        limiter: Boolean,
    ) {
        filterBuffer(count)
        if (limiter) {
            if (!limiterActive) resetLimiter()
            limiterActive = true
            limitFrames(count, outputGain)
        } else {
            limiterActive = false
            gainReductionDb = 0.0
            if (outputGain != 1.0) for (i in 0 until count) processingBuffer[i] *= outputGain
        }
    }

    private fun filterBuffer(count: Int) {
        for (index in activeFilters) {
            val c = coefficients[index]
            val b0 = c[0]
            val b1 = c[1]
            val b2 = c[2]
            val a1 = c[3]
            val a2 = c[4]
            val s = state[index]
            for (channel in 0 until channelCount) {
                val o = channel * 4
                var x1 = s[o]
                var x2 = s[o + 1]
                var y1 = s[o + 2]
                var y2 = s[o + 3]
                var i = channel
                while (i < count) {
                    val x = processingBuffer[i]
                    val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1
                    x1 = x
                    y2 = y1
                    y1 = y
                    processingBuffer[i] = y
                    i += channelCount
                }
                s[o] = x1
                s[o + 1] = x2
                s[o + 2] = y1
                s[o + 3] = y2
            }
        }
        if (preampGain != 1.0) for (i in 0 until count) processingBuffer[i] *= preampGain
    }

    /*
     * Lookahead peak limiter with linked channels. Each frame's target gain is held as a sliding minimum over the
     * lookahead window, release-smoothed without ever exceeding the held value, then box-averaged over the same
     * window. Every averaged value is at most the target of the frame leaving the delay line, so the ceiling holds
     * while gain changes stay ramped instead of clipping.
     */
    private fun limitFrames(
        count: Int,
        outputGain: Double,
    ) {
        val window = lookaheadFrames
        var minimumGain = 1.0
        var frameStart = 0
        while (frameStart < count) {
            val frameEnd = minOf(frameStart + channelCount, count)
            var peak = 0.0
            for (i in frameStart until frameEnd) peak = maxOf(peak, abs(processingBuffer[i]))
            peak *= outputGain
            val target = if (peak > LIMITER_CEILING) LIMITER_CEILING / peak else 1.0

            if (minimumSize > 0 && minimumFrames[minimumHead] <= frameCounter - window) {
                minimumHead = (minimumHead + 1) % window
                minimumSize--
            }
            while (minimumSize > 0 && minimumValues[(minimumHead + minimumSize - 1) % window] >= target) minimumSize--
            val tail = (minimumHead + minimumSize) % window
            minimumValues[tail] = target
            minimumFrames[tail] = frameCounter
            minimumSize++
            val held = minimumValues[minimumHead]

            releaseEnvelope = if (held < releaseEnvelope) held else held + (releaseEnvelope - held) * releaseDecay
            averageSum += releaseEnvelope - averageWindow[averagePosition]
            averageWindow[averagePosition] = releaseEnvelope
            if (++averagePosition == window) {
                averagePosition = 0
                // Re-sum once per window so floating-point drift cannot accumulate.
                averageSum = averageWindow.sum()
            }
            val gain = (averageSum / window).coerceAtMost(1.0)
            minimumGain = minOf(minimumGain, gain)
            val applied = gain * outputGain

            if (delayFrames == 0) {
                for (i in frameStart until frameEnd) processingBuffer[i] *= applied
            } else {
                val offset = delayPosition * channelCount
                for (i in frameStart until frameEnd) {
                    val slot = offset + (i - frameStart)
                    val delayed = delayLine[slot]
                    delayLine[slot] = processingBuffer[i]
                    processingBuffer[i] = delayed * applied
                }
                delayPosition = (delayPosition + 1) % delayFrames
            }
            frameCounter++
            frameStart = frameEnd
        }
        gainReductionDb = -20.0 * log10(minimumGain)
    }

    private fun resetLimiter() {
        delayLine.fill(0.0)
        delayPosition = 0
        minimumHead = 0
        minimumSize = 0
        averageWindow.fill(1.0)
        averagePosition = 0
        averageSum = lookaheadFrames.toDouble()
        releaseEnvelope = 1.0
        frameCounter = 0L
    }

    fun reset() {
        state.forEach { it.fill(0.0) }
        resetLimiter()
        gainReductionDb = 0.0
    }

    private fun coefficientsFor(band: ParametricEqBand): DoubleArray {
        val a = 10.0.pow(band.gain / 40.0)
        val w = 2.0 * PI * band.frequency / sampleRate
        val cosW = cos(w)
        val alpha = sin(w) / (2.0 * band.q)
        val shelf = 2.0 * sqrt(a) * alpha
        val (b, aCoeffs) =
            when (band.filterType) {
                FilterType.PK -> {
                    doubleArrayOf(1 + alpha * a, -2 * cosW, 1 - alpha * a) to
                        doubleArrayOf(1 + alpha / a, -2 * cosW, 1 - alpha / a)
                }

                FilterType.LSC -> {
                    doubleArrayOf(
                        a * ((a + 1) - (a - 1) * cosW + shelf),
                        2 * a * ((a - 1) - (a + 1) * cosW),
                        a * ((a + 1) - (a - 1) * cosW - shelf),
                    ) to doubleArrayOf((a + 1) + (a - 1) * cosW + shelf, -2 * ((a - 1) + (a + 1) * cosW), (a + 1) + (a - 1) * cosW - shelf)
                }

                FilterType.HSC -> {
                    doubleArrayOf(
                        a * ((a + 1) + (a - 1) * cosW + shelf),
                        -2 * a * ((a - 1) + (a + 1) * cosW),
                        a * ((a + 1) + (a - 1) * cosW - shelf),
                    ) to doubleArrayOf((a + 1) - (a - 1) * cosW + shelf, 2 * ((a - 1) - (a + 1) * cosW), (a + 1) - (a - 1) * cosW - shelf)
                }

                FilterType.LPQ -> {
                    doubleArrayOf((1 - cosW) / 2, 1 - cosW, (1 - cosW) / 2) to doubleArrayOf(1 + alpha, -2 * cosW, 1 - alpha)
                }

                FilterType.HPQ -> {
                    doubleArrayOf((1 + cosW) / 2, -(1 + cosW), (1 + cosW) / 2) to doubleArrayOf(1 + alpha, -2 * cosW, 1 - alpha)
                }
            }
        val a0 = aCoeffs[0]
        return doubleArrayOf(b[0] / a0, b[1] / a0, b[2] / a0, aCoeffs[1] / a0, aCoeffs[2] / a0)
    }

    private companion object {
        const val LIMITER_CEILING = 0.9660508789898133 // -0.3 dBFS
        const val LIMITER_LOOKAHEAD_SECONDS = 0.0015
        const val LIMITER_RELEASE_SECONDS = 0.15
    }
}

/**
 * Preamp that keeps the combined band response at or below -0.5 dBFS:
 * -(highest positive gain + 0.5 dB), or 0 dB when no frequency is boosted.
 */
fun EqualizerProfile.autoPreampDb(sampleRate: Int = AUTO_PREAMP_SAMPLE_RATE): Double {
    val analyzer = ParametricEqualizer(sampleRate, 1).apply { update(copy(preamp = 0.0)) }
    val maxFrequency = minOf(MAX_EQUALIZER_FREQUENCY, sampleRate * 0.475)
    val span = ln(maxFrequency / MIN_EQUALIZER_FREQUENCY)
    var peak =
        bands
            .filter { it.enabled && it.frequency in MIN_EQUALIZER_FREQUENCY..maxFrequency }
            .maxOfOrNull { analyzer.responseDb(it.frequency) } ?: Double.NEGATIVE_INFINITY
    for (index in 0 until AUTO_PREAMP_POINTS) {
        peak = maxOf(peak, analyzer.responseDb(MIN_EQUALIZER_FREQUENCY * exp(index * span / (AUTO_PREAMP_POINTS - 1))))
    }
    if (!(peak > 0.0)) return 0.0
    return (-ceil((peak + 0.5) * 10.0) / 10.0).coerceIn(MIN_PROFILE_PREAMP_DB, MAX_PROFILE_PREAMP_DB)
}

private const val AUTO_PREAMP_SAMPLE_RATE = 48_000
private const val AUTO_PREAMP_POINTS = 512
