package com.metrolist.core.api.eq

import com.metrolist.shared.equalizer.CrossfeedPreset
import com.metrolist.shared.equalizer.EqualizerPreset
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.MAX_EQUALIZER_BANDS
import com.metrolist.shared.equalizer.ParametricEqualizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round

/** api.md crossfeed names: "off" | "default" | "chuMoy" | "janMeier". */
object CrossfeedMap {
    /** `enabled` false means "off" (the stored preset is kept); `preset` is null then. */
    data class Crossfeed(
        val enabled: Boolean,
        val preset: CrossfeedPreset?,
    )

    fun toApi(
        enabled: Boolean,
        preset: CrossfeedPreset,
    ): String =
        if (!enabled) {
            "off"
        } else {
            when (preset) {
                CrossfeedPreset.DEFAULT -> "default"
                CrossfeedPreset.CHU_MOY -> "chuMoy"
                CrossfeedPreset.JAN_MEIER -> "janMeier"
            }
        }

    /** Null for an unknown name (the handler answers `bad_request`). */
    fun fromApi(value: String): Crossfeed? =
        when (value) {
            "off" -> Crossfeed(false, null)
            "default" -> Crossfeed(true, CrossfeedPreset.DEFAULT)
            "chuMoy" -> Crossfeed(true, CrossfeedPreset.CHU_MOY)
            "janMeier" -> Crossfeed(true, CrossfeedPreset.JAN_MEIER)
            else -> null
        }
}

/** One row of `EqState.profiles`. */
data class EqProfileRef(
    val id: String,
    val name: String,
) {
    /** api.md 3.8 / brief: built-in = the factory presets and the AutoEq downloads. */
    val builtin: Boolean get() = isBuiltinId(id)

    companion object {
        fun isBuiltinId(id: String): Boolean = id.startsWith("preset_") || id.startsWith("autoeq:")
    }
}

/** Everything `EqState` is built from, read from the engine once per call so the mapper stays pure. */
data class EqStateInput(
    val enabled: Boolean,
    val profile: EqualizerProfile?,
    val savedProfiles: List<EqualizerProfile>,
    val autoPreamp: Boolean,
    val limiterEnabled: Boolean,
    val limiterReductionDb: Float,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val deviceProfiles: Map<String, String>,
)

object EqStateMapper {
    fun presetName(preset: EqualizerPreset): String =
        when (preset) {
            EqualizerPreset.FLAT -> "Flat"
            EqualizerPreset.BASS -> "Bass"
            EqualizerPreset.TREBLE -> "Treble"
            EqualizerPreset.VOCAL -> "Vocal"
            EqualizerPreset.ROCK -> "Rock"
            EqualizerPreset.V_SHAPE -> "V-Shape"
        }

    /** Factory presets first (the first one is the flat profile), then the saved profiles, newest first. */
    fun profileList(saved: List<EqualizerProfile>): List<EqProfileRef> {
        val presets = EqualizerPreset.entries.map { EqProfileRef(it.profileId, presetName(it)) }
        val presetIds = presets.mapTo(HashSet()) { it.id }
        return presets + saved.filter { it.id !in presetIds }.map { EqProfileRef(it.id, it.name) }
    }

    private fun num(value: Double): JsonPrimitive = JsonPrimitive(if (value.isFinite()) value else 0.0)

    fun toJson(input: EqStateInput): JsonObject {
        val profile = input.profile
        return buildJsonObject {
            put("enabled", input.enabled)
            put("profileId", profile?.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "profiles",
                buildJsonArray {
                    profileList(input.savedProfiles).forEach { ref ->
                        add(
                            buildJsonObject {
                                put("id", ref.id)
                                put("name", ref.name)
                                put("builtin", ref.builtin)
                            },
                        )
                    }
                },
            )
            put(
                "bands",
                JsonArray(
                    profile
                        ?.bands
                        .orEmpty()
                        .map { band ->
                            buildJsonObject {
                                put("type", band.filterType.name)
                                put("freq", num(band.frequency))
                                put("gain", num(band.gain))
                                put("q", num(band.q))
                            }
                        },
                ),
            )
            put("preampDb", num(profile?.preamp ?: 0.0))
            put("autoPreamp", input.autoPreamp)
            put(
                "effects",
                buildJsonObject {
                    put("bassDb", num(profile?.bassDb ?: 0.0))
                    put("trebleDb", num(profile?.trebleDb ?: 0.0))
                    put("widthPct", num(round((profile?.stereoWidth ?: 1.0) * 100.0)))
                    put("crossfeed", CrossfeedMap.toApi(profile?.crossfeedEnabled ?: false, profile?.crossfeedPreset ?: CrossfeedPreset.DEFAULT))
                    put("limiter", input.limiterEnabled)
                    put("outputDb", num(profile?.outputGainDb ?: 0.0))
                },
            )
            put("limiterReductionDb", num(input.limiterReductionDb.toDouble()))
            put("canUndo", input.canUndo)
            put("canRedo", input.canRedo)
            put(
                "deviceProfiles",
                JsonArray(
                    input.deviceProfiles.map { (deviceId, profileId) ->
                        buildJsonObject {
                            put("deviceId", deviceId)
                            put("deviceName", deviceId)
                            put("profileId", profileId)
                        }
                    },
                ),
            )
            put("maxBands", MAX_EQUALIZER_BANDS)
        }
    }
}

/** `eq.response`: the curve the graph draws, from the same biquads the audio path runs. */
object EqResponse {
    const val DEFAULT_RATE = 48_000
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0

    /** `hz_i = 20 * exp(i / (n - 1) * ln(maxF / 20))`, `maxF = min(20000, rate * 0.475)`. */
    fun grid(
        points: Int,
        rate: Int = DEFAULT_RATE,
    ): DoubleArray {
        require(points >= 2)
        val maxF = min(MAX_HZ, rate * 0.475)
        val span = ln(maxF / MIN_HZ)
        return DoubleArray(points) { i -> MIN_HZ * exp(i.toDouble() / (points - 1) * span) }
    }

    /** Response in dB at each grid frequency; a null profile is flat. The output gain is added, as in the audio path. */
    fun compute(
        profile: EqualizerProfile?,
        points: Int,
        rate: Int = DEFAULT_RATE,
    ): Pair<DoubleArray, DoubleArray> {
        val hz = grid(points, rate)
        // Signature: ParametricEqualizer(sampleRate: Int, channelCount: Int); update(profile: EqualizerProfile?): Boolean;
        // responseDb(frequency: Double): Double (requires 0..sampleRate/2).
        val eq = ParametricEqualizer(rate, 1).apply { update(profile) }
        val gain = profile?.outputGainDb ?: 0.0
        return hz to DoubleArray(points) { i -> eq.responseDb(hz[i]) + gain }
    }
}

/** `eq.spectrum`: resample the meter's 18 bands (0..1 levels, 60 dB range) to 32 log-spaced bands as Uint8. */
object SpectrumQuantise {
    const val OUT_BANDS = 32

    fun levelToByte(level: Float): Byte = round(level.coerceIn(0f, 1f) * 255f).toInt().toByte()

    /** Linear interpolation over log frequency between the two nearest source bands; edges are clamped. */
    fun toBytes(
        levels: FloatArray,
        centers: List<Double>,
        outBands: Int = OUT_BANDS,
    ): ByteArray {
        require(levels.size == centers.size && levels.isNotEmpty())
        val lo = centers.first()
        val hi = centers.last()
        val out = ByteArray(outBands)
        for (j in 0 until outBands) {
            val f = if (outBands == 1) lo else lo * exp(j.toDouble() / (outBands - 1) * ln(hi / lo))
            val level =
                if (levels.size == 1 || f <= lo) {
                    levels.first()
                } else if (f >= hi) {
                    levels.last()
                } else {
                    var k = 0
                    while (k < centers.size - 2 && centers[k + 1] < f) k++
                    val t = (ln(f / centers[k]) / ln(centers[k + 1] / centers[k])).coerceIn(0.0, 1.0)
                    (levels[k] + (levels[k + 1] - levels[k]) * t.toFloat())
                }
            out[j] = levelToByte(level)
        }
        return out
    }
}

/**
 * Undo/redo of whole profiles, capped at [cap]. Pushes with the same non-null key within [coalesceMs] (a slider or a
 * node being dragged) count as one step.
 */
class EqHistory(
    private val cap: Int = 50,
    private val coalesceMs: Long = 400,
) {
    private val undoStack = ArrayDeque<EqualizerProfile>()
    private val redoStack = ArrayDeque<EqualizerProfile>()
    private var lastKey: String? = null
    private var lastAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Call with the profile as it is BEFORE the edit. */
    fun push(
        before: EqualizerProfile,
        key: String? = null,
        nowMs: Long = 0L,
    ) {
        if (key != null && key == lastKey && nowMs - lastAt in 0 until coalesceMs) {
            lastAt = nowMs
            return
        }
        undoStack.addLast(before)
        while (undoStack.size > cap) undoStack.removeFirst()
        redoStack.clear()
        lastKey = key
        lastAt = nowMs
    }

    /** The profile to restore, or null when there is nothing to undo; [current] becomes the redo step. */
    fun undo(current: EqualizerProfile): EqualizerProfile? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastKey = null
        return previous
    }

    fun redo(current: EqualizerProfile): EqualizerProfile? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastKey = null
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastKey = null
    }
}
