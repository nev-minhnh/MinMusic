/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import com.metrolist.shared.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
enum class FilterType { PK, LSC, HSC, LPQ, HPQ, BP, NO }

@Serializable
data class ParametricEqBand(
    val frequency: Double,
    val gain: Double,
    val q: Double = 1.41,
    val filterType: FilterType = FilterType.PK,
    val enabled: Boolean = true,
)

internal val ParametricEqBand.supportsGain: Boolean
    get() = filterType == FilterType.PK || filterType == FilterType.LSC || filterType == FilterType.HSC

internal fun ParametricEqBand.withAdjustedGain(gain: Double): ParametricEqBand =
    if (!supportsGain || gain.isNaN() || gain == this.gain) {
        this
    } else {
        copy(gain = (round(gain * 2) / 2).coerceIn(MIN_EQUALIZER_BAND_GAIN_DB, MAX_EQUALIZER_BAND_GAIN_DB))
    }

internal val FilterType.supportsGain: Boolean
    get() = this == FilterType.PK || this == FilterType.LSC || this == FilterType.HSC

// Zero-gain peaking and shelf filters have identical numerator and denominator coefficients.
internal fun ParametricEqBand.isIdentity(): Boolean =
    !enabled || (gain == 0.0 && (filterType == FilterType.PK || filterType == FilterType.LSC || filterType == FilterType.HSC))

@Serializable
data class EqualizerProfile(
    val id: String,
    val name: String,
    val bands: List<ParametricEqBand>,
    val preamp: Double = 0.0,
    val source: String = "unknown",
    val rig: String = "unknown",
    val addedTimestamp: Long = 0L,
    val outputGainDb: Double? = null,
)

// Keep storage indices stable for per-band reset and the DSP filter history.
internal val EqualizerProfile.bandsByFrequency: List<IndexedValue<ParametricEqBand>>
    get() = bands.withIndex().sortedBy { it.value.frequency }

internal fun EqualizerProfile.withAdjustedBand(
    index: Int,
    gain: Double,
    frequency: Double,
    maxFrequency: Double,
): EqualizerProfile {
    require(maxFrequency in MIN_EQUALIZER_FREQUENCY..MAX_EQUALIZER_FREQUENCY)
    val old = bands[index]
    if (!old.enabled || old.frequency !in MIN_EQUALIZER_FREQUENCY..maxFrequency || frequency.isNaN()) return this
    val ordered = bandsByFrequency
    val position = ordered.indexOfFirst { it.index == index }
    // Leave one Hz between neighbours, without moving closely spaced imported filters.
    val low =
        maxOf(
            MIN_EQUALIZER_FREQUENCY,
            ordered.getOrNull(position - 1)?.let { minOf(old.frequency, it.value.frequency + 1.0) } ?: MIN_EQUALIZER_FREQUENCY,
        )
    val high =
        minOf(
            maxFrequency,
            ordered.getOrNull(position + 1)?.let { maxOf(old.frequency, it.value.frequency - 1.0) } ?: maxFrequency,
        )
    val updated = old.withAdjustedGain(gain).copy(frequency = frequency.coerceIn(low, high))
    return if (old == updated) this else copy(bands = bands.toMutableList().apply { this[index] = updated })
}

fun createEqualizerBands(
    count: Int = 10,
    minFrequency: Double = MIN_EQUALIZER_FREQUENCY,
    maxFrequency: Double = MAX_EQUALIZER_FREQUENCY,
): List<ParametricEqBand> {
    require(count in 1..MAX_EQUALIZER_BANDS)
    require(minFrequency.isFinite() && maxFrequency.isFinite() && minFrequency > 0.0 && maxFrequency > minFrequency)
    val ratio = (maxFrequency / minFrequency).pow(1.0 / count)
    val q = sqrt(ratio) / (ratio - 1.0)
    return List(count) { index -> ParametricEqBand(minFrequency * ratio.pow(index + 0.5), 0.0, q) }
}

class InvalidEqualizerProfileException : IllegalArgumentException()

data class EqualizerConfiguration(
    val profile: EqualizerProfile?,
    val preampDb: Double,
    val limiterEnabled: Boolean = true,
) {
    val isIdentity: Boolean
        get() = preampDb == 0.0 && (profile == null || (profile.preamp == 0.0 && profile.bands.all { it.isIdentity() }))
}

enum class EqualizerPreset(
    val gains: List<Double>,
) {
    FLAT(List(10) { 0.0 }),
    BASS(listOf(6.0, 5.0, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    TREBLE(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0)),
    VOCAL(listOf(-3.0, -2.0, -1.0, 0.0, 2.0, 3.0, 3.0, 1.0, 0.0, -1.0)),
    ROCK(listOf(4.0, 3.0, 1.0, -1.0, -2.0, -1.0, 1.0, 2.0, 3.0, 4.0)),
    V_SHAPE(listOf(5.0, 4.0, 2.0, 0.0, -2.0, -2.0, 0.0, 2.0, 4.0, 5.0)),
    ;

    val profileId: String get() = "preset_${name.lowercase()}"
}

object EqualizerProfiles {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cache: Pair<String, List<EqualizerProfile>> = "" to emptyList()

    @Volatile private var currentCache: Pair<String, EqualizerProfile?> = "" to null

    val profiles: List<EqualizerProfile>
        get() = decode(Settings.shared.equalizerProfilesJson)

    private val workingProfile: EqualizerProfile?
        get() {
            val raw = Settings.shared.equalizerCurrentProfileJson
            currentCache.let { (cached, profile) -> if (cached == raw) return profile }
            val profile = if (raw.isEmpty()) null else runCatching { json.decodeFromString<EqualizerProfile>(raw) }.getOrNull()
            currentCache = raw to profile
            return profile
        }

    val selectedConfiguration: EqualizerConfiguration
        get() {
            workingProfile?.let { return EqualizerConfiguration(it, it.outputGainDb ?: Settings.shared.audioBoostDb.toDouble()) }
            val id = Settings.shared.equalizerActiveProfileId
            val profile = profiles.firstOrNull { it.id == id }
            return EqualizerConfiguration(profile, profile?.outputGainDb ?: Settings.shared.audioBoostDb.toDouble())
        }

    val configuration: EqualizerConfiguration
        get() =
            selectedConfiguration.takeIf { Settings.shared.equalizerEnabled }?.copy(limiterEnabled = limiterEnabled)
                ?: EqualizerConfiguration(null, 0.0)

    var limiterEnabled: Boolean
        get() = Settings.shared.equalizerLimiterEnabled
        set(value) {
            Settings.shared.equalizerLimiterEnabled = value
        }

    val isEnabled: Boolean
        get() = Settings.shared.equalizerEnabled && (activeProfile != null || selectedConfiguration.preampDb != 0.0)

    fun setEnabled(enabled: Boolean) {
        if (enabled) ensureDefaultProfile()
        Settings.shared.equalizerEnabled = enabled
    }

    fun ensureDefaultProfile() {
        if (activeProfile != null) return
        activate(
            presetProfile(EqualizerPreset.FLAT, EqualizerPreset.FLAT.name).copy(
                outputGainDb = Settings.shared.audioBoostDb.toDouble(),
            ),
            Settings.shared.equalizerEnabled,
        )
    }

    val activeProfile: EqualizerProfile? get() = selectedConfiguration.profile
    val activeProfileId: String? get() = activeProfile?.id

    val hasUnsavedChanges: Boolean
        get() {
            val current = selectedConfiguration
            val profile = current.profile ?: return current.preampDb != 0.0
            val baseline = baselineProfile(profile)
            return baseline != profile.copy(outputGainDb = current.preampDb)
        }

    fun setActive(profileId: String?) {
        val profile = profiles.firstOrNull { it.id == profileId }
        if (profile != null) {
            activate(profile)
        } else {
            Settings.shared.equalizerActiveProfileId = ""
            Settings.shared.equalizerCurrentProfileJson = ""
        }
    }

    fun delete(profileId: String) {
        val active = activeProfileId == profileId
        save(profiles.filterNot { it.id == profileId })
        if (active) {
            setEnabled(false)
            setActive(null)
        }
    }

    fun add(
        profile: EqualizerProfile,
        enabled: Boolean = true,
    ) {
        val stamped =
            profile.copy(
                addedTimestamp = Clock.System.now().toEpochMilliseconds(),
                outputGainDb =
                    (
                        profile.outputGainDb
                            ?: Settings.shared.audioBoostDb.toDouble()
                    ).coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB),
            )
        save(listOf(stamped) + profiles.filterNot { it.id == profile.id })
        activate(stamped, enabled)
    }

    fun importProfile(profile: EqualizerProfile) {
        add(profile.copy(id = "custom_${Uuid.random()}"))
    }

    fun createCustom(name: String): EqualizerProfile {
        add(newCustom(name).copy(outputGainDb = null))
        return checkNotNull(activeProfile)
    }

    fun activatePreset(
        preset: EqualizerPreset,
        name: String,
    ) {
        activate(presetProfile(preset, name))
    }

    private fun presetProfile(
        preset: EqualizerPreset,
        name: String,
    ) = EqualizerProfile(
        id = preset.profileId,
        name = name,
        bands = createEqualizerBands(preset.gains.size).zip(preset.gains) { band, gain -> band.copy(gain = gain) },
        source = "preset",
        outputGainDb = 0.0,
    )

    fun editCurrent(profile: EqualizerProfile) {
        require(profile.id == activeProfileId)
        Settings.shared.equalizerCurrentProfileJson = json.encodeToString(profile.copy(outputGainDb = selectedConfiguration.preampDb))
    }

    fun addBand(
        minFrequency: Double,
        maxFrequency: Double,
    ) {
        require(minFrequency.isFinite() && maxFrequency.isFinite() && minFrequency > 0.0 && maxFrequency > minFrequency)
        val current = activeProfile ?: return
        if (current.bands.size >= MAX_EQUALIZER_BANDS) return
        val boundaries =
            (
                listOf(minFrequency, maxFrequency) +
                    current.bands
                        .map { it.frequency }
                        .filter { it in minFrequency..maxFrequency }
            ).sorted()
        val (low, high) = boundaries.zipWithNext().maxBy { (low, high) -> high / low }
        val band = ParametricEqBand(sqrt(low * high), 0.0, q = sqrt(low * high) / (high - low))
        editCurrent(current.copy(bands = current.bands + band))
    }

    fun removeBand(index: Int) {
        val current = activeProfile ?: return
        require(index in current.bands.indices)
        editCurrent(current.copy(bands = current.bands.filterIndexed { bandIndex, _ -> bandIndex != index }))
    }

    fun setPreamp(db: Double) {
        val bounded = (round(db * 2) / 2).coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB)
        val current = activeProfile
        Settings.shared.audioBoostDb = bounded.toFloat()
        if (current != null) Settings.shared.equalizerCurrentProfileJson = json.encodeToString(current.copy(outputGainDb = bounded))
    }

    fun updateBand(
        index: Int,
        transform: (ParametricEqBand) -> ParametricEqBand,
    ) {
        val current = activeProfile ?: return
        val band = current.bands.getOrNull(index) ?: return
        val updated = transform(band)
        if (updated != band) editCurrent(current.copy(bands = current.bands.toMutableList().apply { this[index] = updated }))
    }

    fun setBandType(
        index: Int,
        type: FilterType,
    ) = updateBand(index) { band -> band.copy(filterType = type, gain = if (type.supportsGain) band.gain else 0.0) }

    fun setProfilePreamp(db: Double) {
        val current = activeProfile ?: return
        if (db.isNaN()) return
        val bounded = (round(db * 10) / 10).coerceIn(MIN_PROFILE_PREAMP_DB, MAX_PROFILE_PREAMP_DB)
        if (bounded != current.preamp) editCurrent(current.copy(preamp = bounded))
    }

    fun applyAutoPreamp() {
        activeProfile?.let { setProfilePreamp(it.autoPreampDb()) }
    }

    fun saveCurrent(
        name: String,
        saveAs: Boolean = false,
    ): EqualizerProfile {
        require(name.isNotBlank())
        val current = selectedConfiguration
        val profile = current.profile ?: newCustom(name)
        val existing = profiles.any { it.id == profile.id }
        val saved =
            profile.copy(
                id = if (existing && !saveAs) profile.id else "custom_${Uuid.random()}",
                name = name.trim(),
                outputGainDb = current.preampDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB),
            )
        add(saved, Settings.shared.equalizerEnabled)
        return checkNotNull(activeProfile)
    }

    fun resetBand(index: Int) {
        val current = activeProfile ?: return
        require(index in current.bands.indices)
        val original = baselineProfile(current)?.bands?.getOrNull(index) ?: current.bands[index].copy(gain = 0.0)
        val updated = current.bands.toMutableList()
        updated[index] = original
        editCurrent(current.copy(bands = updated))
    }

    fun resetAllBands() {
        val current = activeProfile ?: return
        val original = baselineProfile(current) ?: return
        editCurrent(current.copy(bands = original.bands))
    }

    fun bandHasChanges(index: Int): Boolean {
        val current = activeProfile ?: return false
        val band = current.bands.getOrNull(index) ?: return false
        return band != baselineProfile(current)?.bands?.getOrNull(index)
    }

    private fun baselineProfile(profile: EqualizerProfile): EqualizerProfile? {
        val preset = EqualizerPreset.entries.firstOrNull { it.profileId == profile.id }
        return preset?.let { presetProfile(it, profile.name) } ?: profiles.firstOrNull { it.id == profile.id }
    }

    private fun newCustom(name: String): EqualizerProfile {
        require(name.isNotBlank())
        return EqualizerProfile(
            id = "custom_${Uuid.random()}",
            name = name.trim(),
            bands = createEqualizerBands(),
            source = "custom",
            outputGainDb = 0.0,
        )
    }

    private fun activate(
        profile: EqualizerProfile,
        enabled: Boolean = true,
    ) {
        val bounded =
            profile.copy(
                outputGainDb =
                    (profile.outputGainDb ?: Settings.shared.audioBoostDb.toDouble()).coerceIn(
                        MIN_AUDIO_PREAMP_DB,
                        MAX_AUDIO_BOOST_DB,
                    ),
            )
        Settings.shared.audioBoostDb = checkNotNull(bounded.outputGainDb).toFloat()
        Settings.shared.equalizerActiveProfileId = bounded.id
        Settings.shared.equalizerEnabled = enabled
        Settings.shared.equalizerCurrentProfileJson = json.encodeToString(bounded)
    }

    fun importText(
        fileName: String,
        content: String,
    ): EqualizerProfile {
        val (preamp, bands) = parseEqualizerText(content) ?: throw InvalidEqualizerProfileException()
        val name =
            fileName
                .substringBeforeLast('.')
                .removeSuffix(" ParametricEQ")
                .removeSuffix(" GraphicEQ")
                .ifBlank { fileName }
        val profile = EqualizerProfile("custom_${Uuid.random()}", name, bands, preamp = preamp, outputGainDb = 0.0)
        add(profile)
        return checkNotNull(activeProfile)
    }

    fun migrateLegacy(
        legacyJson: String?,
        legacyActiveId: String?,
    ): Boolean {
        if (Settings.shared.equalizerProfilesJson.isNotEmpty() || legacyJson.isNullOrBlank()) return false
        val legacy = runCatching { json.decodeFromString<List<EqualizerProfile>>(legacyJson) }.getOrNull() ?: return false
        save(legacy)
        setActive(legacyActiveId?.takeIf { id -> legacy.any { it.id == id } })
        return true
    }

    private fun save(profiles: List<EqualizerProfile>) {
        Settings.shared.equalizerProfilesJson = json.encodeToString(profiles.sortedByDescending { it.addedTimestamp })
    }

    private fun decode(raw: String): List<EqualizerProfile> {
        cache.let { (cachedRaw, cachedProfiles) -> if (cachedRaw == raw) return cachedProfiles }
        val decoded =
            if (raw.isEmpty()) {
                emptyList()
            } else {
                runCatching {
                    json.decodeFromString<List<EqualizerProfile>>(raw)
                }.getOrDefault(emptyList())
            }
        cache = raw to decoded
        return decoded
    }
}

private val filterTypes =
    mapOf(
        "PK" to FilterType.PK,
        "PEQ" to FilterType.PK,
        "LS" to FilterType.LSC,
        "LSC" to FilterType.LSC,
        "HS" to FilterType.HSC,
        "HSC" to FilterType.HSC,
        "LP" to FilterType.LPQ,
        "LPQ" to FilterType.LPQ,
        "HP" to FilterType.HPQ,
        "HPQ" to FilterType.HPQ,
        "BP" to FilterType.BP,
        "NO" to FilterType.NO,
    )

/** Parses AutoEq/Equalizer APO text; returns null for unusable or out-of-range profiles. */
fun parseParametricEq(content: String): Pair<Double, List<ParametricEqBand>>? {
    var preamp = 0.0
    val bands = mutableListOf<ParametricEqBand>()
    for (line in content.lineSequence()) {
        val tokens = line.trim().split(Regex("\\s+"))

        fun after(key: String) =
            tokens
                .indexOfFirst {
                    it.equals(key, ignoreCase = true)
                }.takeIf { it >= 0 }
                ?.let { tokens.getOrNull(it + 1)?.toDoubleOrNull() }
        when {
            tokens.first().equals("Preamp:", ignoreCase = true) -> {
                preamp = tokens.getOrNull(1)?.toDoubleOrNull() ?: return null
            }

            tokens.first().startsWith("Filter", ignoreCase = true) -> {
                // Exported profiles keep disabled bands as OFF filters.
                val enabled = tokens.any { it.equals("ON", ignoreCase = true) }
                if (!enabled && tokens.none { it.equals("OFF", ignoreCase = true) }) continue
                val type = tokens.firstNotNullOfOrNull { filterTypes[it.uppercase()] } ?: continue
                val frequency = after("Fc") ?: continue
                // Pass, band-pass and notch filters carry no gain; shelves without Q use the cookbook's S=1 slope (Q 0.707).
                val gain = after("Gain") ?: if (type.supportsGain) continue else 0.0
                bands += ParametricEqBand(frequency, gain, after("Q") ?: 0.707, type, enabled)
            }
        }
    }
    val valid =
        bands.isNotEmpty() &&
            bands.size <= MAX_EQUALIZER_BANDS &&
            preamp.isFinite() &&
            bands.all {
                it.frequency in 1.0..100_000.0 && it.gain in MIN_EQUALIZER_BAND_GAIN_DB..MAX_EQUALIZER_BAND_GAIN_DB && it.q > 0.0 &&
                    it.q <= 20.0
            }
    return if (valid) preamp.coerceIn(MIN_PROFILE_PREAMP_DB, MAX_PROFILE_PREAMP_DB) to bands else null
}

/** Parses Equalizer APO ParametricEQ text, falling back to an AutoEq GraphicEQ curve. */
fun parseEqualizerText(content: String): Pair<Double, List<ParametricEqBand>>? = parseParametricEq(content) ?: parseGraphicEq(content)

/**
 * Converts an AutoEq "GraphicEQ: f g; f g; ..." curve to log-spaced peaking bands, one per third of an octave
 * from 20 Hz to 20 kHz. Each band starts at the curve's gain at its centre (linear interpolation over log
 * frequency), then the gains are corrected a few times by the difference between the curve and the summed
 * response at every centre, which removes most of the overlap between neighbouring bands.
 */
fun parseGraphicEq(content: String): Pair<Double, List<ParametricEqBand>>? {
    val line = content.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("GraphicEQ:", ignoreCase = true) } ?: return null
    val points =
        line
            .substringAfter(':')
            .split(';')
            .mapNotNull { pair ->
                val values = pair.trim().split(Regex("\\s+"))
                val frequency = values.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val gain = values.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                (frequency to gain).takeIf { frequency > 0.0 && frequency.isFinite() && gain.isFinite() }
            }.sortedBy { it.first }
    if (points.size < 2) return null

    fun curve(frequency: Double): Double {
        if (frequency <= points.first().first) return points.first().second
        if (frequency >= points.last().first) return points.last().second
        val upper = points.indexOfFirst { it.first >= frequency }
        val (f0, g0) = points[upper - 1]
        val (f1, g1) = points[upper]
        return if (f1 == f0) g1 else g0 + (g1 - g0) * ln(frequency / f0) / ln(f1 / f0)
    }

    val template = createEqualizerBands(GRAPHIC_EQ_BANDS)
    val targets = template.map { curve(it.frequency) }
    val gains = targets.toDoubleArray()
    val analyzer = ParametricEqualizer(GRAPHIC_EQ_SAMPLE_RATE, 1)
    repeat(GRAPHIC_EQ_ITERATIONS) {
        analyzer.update(EqualizerProfile("graphic", "graphic", template.mapIndexed { index, band -> band.copy(gain = gains[index]) }))
        val responses = template.map { analyzer.responseDb(it.frequency) }
        for (index in gains.indices) {
            gains[index] = (gains[index] + targets[index] - responses[index]).coerceIn(MIN_EQUALIZER_BAND_GAIN_DB, MAX_EQUALIZER_BAND_GAIN_DB)
        }
    }
    return 0.0 to template.mapIndexed { index, band -> band.copy(gain = round(gains[index] * 10) / 10) }
}

/** Equalizer APO ParametricEQ text; disabled bands are written as OFF filters. */
fun EqualizerProfile.toParametricEqText(): String =
    buildString {
        append("Preamp: ").append(preamp.toDecimalString(1)).append(" dB\n")
        bands.forEachIndexed { index, band ->
            append("Filter ").append(index + 1).append(": ").append(if (band.enabled) "ON " else "OFF ")
            append(
                when (band.filterType) {
                    FilterType.PK -> "PK"
                    FilterType.LSC -> "LSC"
                    FilterType.HSC -> "HSC"
                    FilterType.LPQ -> "LPQ"
                    FilterType.HPQ -> "HPQ"
                    FilterType.BP -> "BP"
                    FilterType.NO -> "NO"
                },
            )
            append(" Fc ").append(band.frequency.toDecimalString(1)).append(" Hz")
            if (band.supportsGain) append(" Gain ").append(band.gain.toDecimalString(2)).append(" dB")
            append(" Q ").append(band.q.toDecimalString(3)).append('\n')
        }
    }

private const val GRAPHIC_EQ_BANDS = 30
private const val GRAPHIC_EQ_ITERATIONS = 12
private const val GRAPHIC_EQ_SAMPLE_RATE = 48_000

/** Locale-independent fixed-point text, as Equalizer APO files and numeric entry expect. */
internal fun Double.toDecimalString(decimals: Int): String {
    val scaled = round(this * 10.0.pow(decimals)).toLong()
    val digits = abs(scaled).toString().padStart(decimals + 1, '0')
    val text = if (decimals == 0) digits else digits.dropLast(decimals) + "." + digits.takeLast(decimals)
    return if (scaled < 0) "-$text" else text
}

const val MAX_EQUALIZER_BANDS = 32
const val MIN_EQUALIZER_BAND_GAIN_DB = -30.0
const val MAX_EQUALIZER_BAND_GAIN_DB = 30.0
const val MIN_EQUALIZER_FREQUENCY = 20.0
const val MAX_EQUALIZER_FREQUENCY = 20_000.0
