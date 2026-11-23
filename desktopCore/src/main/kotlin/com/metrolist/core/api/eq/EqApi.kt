package com.metrolist.core.api.eq

import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.bool
import com.metrolist.core.server.double
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.notFound
import com.metrolist.core.server.requireBool
import com.metrolist.core.server.requireDouble
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.core.server.topic
import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.equalizer.AutoEqCatalog
import com.metrolist.shared.equalizer.AutoEqEntry
import com.metrolist.shared.equalizer.EqualizerPreset
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.equalizer.FilterType
import com.metrolist.shared.equalizer.InvalidEqualizerProfileException
import com.metrolist.shared.equalizer.MAX_EQUALIZER_BANDS
import com.metrolist.shared.equalizer.MAX_EQUALIZER_BAND_GAIN_DB
import com.metrolist.shared.equalizer.MAX_EQUALIZER_FREQUENCY
import com.metrolist.shared.equalizer.MAX_STEREO_WIDTH
import com.metrolist.shared.equalizer.MAX_TONE_DB
import com.metrolist.shared.equalizer.MIN_EQUALIZER_BAND_GAIN_DB
import com.metrolist.shared.equalizer.MIN_EQUALIZER_FREQUENCY
import com.metrolist.shared.equalizer.ParametricEqBand
import com.metrolist.shared.equalizer.parseEqualizerText
import com.metrolist.shared.equalizer.searchAutoEqModels
import com.metrolist.shared.equalizer.toParametricEqText
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/** Change counter behind the `eq` topic: every mutating handler bumps it, the topic re-reads the state. */
object EqBus {
    val version = MutableStateFlow(0L)

    fun changed() {
        version.value = version.value + 1
    }
}

/** `eqAutoPreamp` flag, kept in a tiny file under the core data root (the core has no generic prefs store yet). */
internal object EqAutoPreampStore {
    private fun file(): File? = System.getProperty("minmusic.dataRoot")?.takeIf { it.isNotBlank() }?.let { File(it, "eq-auto-preamp.flag") }

    fun load(): Boolean = runCatching { file()?.takeIf { it.exists() }?.readText()?.trim() == "1" }.getOrDefault(false)

    fun save(on: Boolean) {
        runCatching {
            file()?.let {
                it.parentFile?.mkdirs()
                it.writeText(if (on) "1" else "0")
            }
        }
    }
}

/**
 * api.md 3.8 and the `eq` topic. The engine (EqualizerProfiles and friends, package com.metrolist.shared.equalizer) is
 * a set of plain objects over Settings, so handlers may run on any thread; [lock] only keeps the push-undo, edit,
 * auto-preamp sequence of one request atomic.
 */
object EqApi {
    private val lock = Mutex()
    private val history = EqHistory()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var autoPreamp: Boolean = EqAutoPreampStore.load()

    private fun locked(block: suspend (JsonObject) -> JsonElement): Handler =
        handler { params, _ ->
            backendReady()
            lock.withLock { block(params) }
        }

    // --- state -----------------------------------------------------------------------------------------------------

    private fun deviceProfiles(): Map<String, String> =
        Settings.shared.equalizerDeviceProfilesJson
            .takeIf { it.isNotEmpty() }
            ?.let { raw -> runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull() }
            .orEmpty()

    private fun stateJson(): JsonObject =
        EqStateMapper.toJson(
            EqStateInput(
                enabled = Settings.shared.equalizerEnabled,
                profile = EqualizerProfiles.activeProfile,
                savedProfiles = EqualizerProfiles.profiles,
                autoPreamp = autoPreamp,
                limiterEnabled = EqualizerProfiles.limiterEnabled,
                limiterReductionDb = AudioSpectrumMeter.gainReductionDb,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                deviceProfiles = deviceProfiles(),
            ),
        )

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun active(): EqualizerProfile {
        // Signature: ensureDefaultProfile() activates the flat preset when no profile is active.
        EqualizerProfiles.ensureDefaultProfile()
        return EqualizerProfiles.activeProfile ?: throw ApiException("conflict", "no active equalizer profile")
    }

    private fun bandIndex(
        params: JsonObject,
        profile: EqualizerProfile,
    ): Int {
        val index = params.double("index")?.takeIf { it == Math.floor(it) }?.toInt() ?: badRequest("missing integer param 'index'")
        if (index !in profile.bands.indices) badRequest("band index out of range")
        return index
    }

    private fun finite(
        params: JsonObject,
        key: String,
    ): Double? {
        val value = params[key]
        if (value == null || value is JsonNull) return null
        return params.double(key)?.takeIf { it.isFinite() } ?: badRequest("'$key' must be a finite number")
    }

    /** Pushes the undo step, runs the edit, re-applies the auto preamp, notifies the topic. */
    private fun edit(
        key: String? = null,
        block: (EqualizerProfile) -> Unit,
    ): JsonElement {
        val before = active()
        history.push(before, key, System.currentTimeMillis())
        block(before)
        // Signature: applyAutoPreamp() sets the profile preamp from the current band response (one-shot).
        if (autoPreamp) EqualizerProfiles.applyAutoPreamp()
        EqBus.changed()
        return emptyResult()
    }

    private fun savedProfile(id: String): EqualizerProfile? = EqualizerProfiles.profiles.firstOrNull { it.id == id }

    private fun presetOf(id: String): EqualizerPreset? = EqualizerPreset.entries.firstOrNull { it.profileId == id }

    private fun resultId(): JsonObject =
        buildJsonObject { put("id", EqualizerProfiles.activeProfileId ?: "") }

    // --- handlers --------------------------------------------------------------------------------------------------

    val handlers: Map<String, Handler> =
        mapOf(
            "eq.get" to locked { stateJson() },
            "eq.setEnabled" to
                locked { p ->
                    EqualizerProfiles.setEnabled(p.requireBool("on"))
                    EqBus.changed()
                    emptyResult()
                },
            "eq.setProfile" to locked { p -> setProfile(p.requireStr("id")) },
            "eq.updateBand" to locked { p -> updateBand(p) },
            "eq.addBand" to locked { p -> addBand(p) },
            "eq.removeBand" to
                locked { p ->
                    val index = bandIndex(p, active())
                    edit { EqualizerProfiles.removeBand(index) }
                },
            "eq.resetBand" to
                locked { p ->
                    val index = bandIndex(p, active())
                    edit { EqualizerProfiles.resetBand(index) }
                },
            "eq.setPreamp" to locked { p -> setPreamp(p.requireDouble("db")) },
            "eq.setAutoPreamp" to locked { p -> setAutoPreamp(p.requireBool("on")) },
            "eq.setEffects" to locked { p -> setEffects(p) },
            "eq.profile.save" to locked { p -> save(p) },
            "eq.profile.rename" to locked { p -> rename(p.requireStr("id"), p.requireStr("name")) },
            "eq.profile.delete" to locked { p -> delete(p.requireStr("id")) },
            "eq.undo" to locked { undoRedo(undo = true) },
            "eq.redo" to locked { undoRedo(undo = false) },
            "eq.reset" to locked { reset() },
            "eq.importFile" to locked { p -> importFile(p.requireStr("path")) },
            "eq.exportFile" to locked { p -> exportFile(p.requireStr("id"), p.requireStr("path")) },
            "eq.autoeq.search" to handler { p, _ -> autoEqSearch(p.str("q").orEmpty()) },
            "eq.autoeq.apply" to handler { p, _ -> autoEqApply(p.requireStr("name"), p.str("variant")) },
            "eq.device.bind" to locked { p -> bindDevice(p) },
            "eq.response" to
                locked { p ->
                    val points = p.double("points")?.toInt() ?: 256
                    if (points !in 2..4096) badRequest("points must be 2..4096")
                    response(points)
                },
        )

    private fun setProfile(id: String): JsonElement {
        val wasEnabled = Settings.shared.equalizerEnabled
        val preset = presetOf(id)
        when {
            // Signature: activatePreset(preset: EqualizerPreset, name: String); setActive(profileId: String?).
            preset != null -> EqualizerProfiles.activatePreset(preset, EqStateMapper.presetName(preset))
            savedProfile(id) != null -> EqualizerProfiles.setActive(id)
            else -> notFound("profile $id")
        }
        // Activating a profile switches the equalizer on in the engine; selecting one must not.
        Settings.shared.equalizerEnabled = wasEnabled
        history.clear()
        EqBus.changed()
        return emptyResult()
    }

    private fun updateBand(p: JsonObject): JsonElement {
        val current = active()
        val index = bandIndex(p, current)
        val type =
            p.str("type")?.let { name -> FilterType.entries.firstOrNull { it.name == name } ?: badRequest("unknown band type '$name'") }
        val freq = finite(p, "freq")?.coerceIn(MIN_EQUALIZER_FREQUENCY, MAX_EQUALIZER_FREQUENCY)
        val gain = finite(p, "gain")?.coerceIn(MIN_EQUALIZER_BAND_GAIN_DB, MAX_EQUALIZER_BAND_GAIN_DB)
        val q = finite(p, "q")?.coerceIn(0.1, 20.0)
        return edit("band:$index") {
            // Signature: updateBand(index: Int, transform: (ParametricEqBand) -> ParametricEqBand); setBandType(index: Int, type: FilterType).
            EqualizerProfiles.updateBand(index) { it.copy(frequency = freq ?: it.frequency, gain = gain ?: it.gain, q = q ?: it.q) }
            if (type != null) EqualizerProfiles.setBandType(index, type)
        }
    }

    private fun addBand(p: JsonObject): JsonElement {
        val current = active()
        val freq = p.requireDouble("freq").takeIf { it.isFinite() } ?: badRequest("freq must be finite")
        if (current.bands.size >= MAX_EQUALIZER_BANDS) badRequest("band limit reached ($MAX_EQUALIZER_BANDS)")
        val band = ParametricEqBand(freq.coerceIn(MIN_EQUALIZER_FREQUENCY, MAX_EQUALIZER_FREQUENCY), 0.0)
        // Appended, not sorted: the engine keys per-band reset and DSP history on the storage index.
        return edit {
            // Signature: editCurrent(profile: EqualizerProfile); requires profile.id == activeProfileId.
            EqualizerProfiles.editCurrent(it.copy(bands = it.bands + band))
        }
    }

    private fun setPreamp(db: Double): JsonElement {
        if (!db.isFinite()) badRequest("db must be finite")
        // An explicit preamp ends auto mode.
        if (autoPreamp) {
            autoPreamp = false
            EqAutoPreampStore.save(false)
        }
        val before = active()
        history.push(before, "preamp", System.currentTimeMillis())
        // Signature: setProfilePreamp(db: Double), the API preampDb (-24..24).
        EqualizerProfiles.setProfilePreamp(db)
        EqBus.changed()
        return emptyResult()
    }

    private fun setAutoPreamp(on: Boolean): JsonElement {
        autoPreamp = on
        EqAutoPreampStore.save(on)
        if (on) {
            history.push(active(), null, System.currentTimeMillis())
            EqualizerProfiles.applyAutoPreamp()
        }
        EqBus.changed()
        return emptyResult()
    }

    private fun setEffects(p: JsonObject): JsonElement {
        val bass = finite(p, "bassDb")?.also { if (it !in -MAX_TONE_DB..MAX_TONE_DB) badRequest("bassDb out of range") }
        val treble = finite(p, "trebleDb")?.also { if (it !in -MAX_TONE_DB..MAX_TONE_DB) badRequest("trebleDb out of range") }
        val width = finite(p, "widthPct")?.also { if (it !in 0.0..MAX_STEREO_WIDTH * 100.0) badRequest("widthPct out of range") }
        val crossfeed = p.str("crossfeed")?.let { CrossfeedMap.fromApi(it) ?: badRequest("crossfeed must be off, default, chuMoy or janMeier") }
        val limiter = if (p["limiter"] == null) null else p.requireBool("limiter")
        val output = finite(p, "outputDb")
        val before = active()
        history.push(before, "fx", System.currentTimeMillis())
        if (bass != null || treble != null || width != null || crossfeed != null) {
            // Signature: editTone(transform: (EqualizerProfile) -> EqualizerProfile); rounds and bounds the tone values itself.
            EqualizerProfiles.editTone {
                it.copy(
                    bassDb = bass ?: it.bassDb,
                    trebleDb = treble ?: it.trebleDb,
                    stereoWidth = width?.div(100.0) ?: it.stereoWidth,
                    crossfeedEnabled = crossfeed?.enabled ?: it.crossfeedEnabled,
                    crossfeedPreset = crossfeed?.preset ?: it.crossfeedPreset,
                )
            }
        }
        // Signature: var limiterEnabled: Boolean; setPreamp(db: Double) is the output gain (-12..12, step 0.5).
        if (limiter != null) EqualizerProfiles.limiterEnabled = limiter
        if (output != null) EqualizerProfiles.setPreamp(output)
        if (autoPreamp) EqualizerProfiles.applyAutoPreamp()
        EqBus.changed()
        return emptyResult()
    }

    private fun save(p: JsonObject): JsonElement {
        val name = p.requireStr("name").trim()
        if (name.isEmpty()) badRequest("name must not be blank")
        val asNew = p.requireBool("asNew")
        val before = EqualizerProfiles.activeProfileId
        // Signature: saveCurrent(name: String, saveAs: Boolean = false): EqualizerProfile (throws on a blank name).
        val saved = EqualizerProfiles.saveCurrent(name, saveAs = asNew)
        if (saved.id != before) history.clear()
        EqBus.changed()
        return buildJsonObject { put("id", saved.id) }
    }

    private fun rename(
        id: String,
        name: String,
    ): JsonElement {
        if (name.isBlank()) badRequest("name must not be blank")
        if (EqProfileRef.isBuiltinId(id)) badRequest("built-in profiles cannot be renamed")
        if (savedProfile(id) == null) notFound("profile $id")
        // The engine's writer is private: activate the saved profile, then save it under the new name.
        EqualizerProfiles.setActive(id)
        EqualizerProfiles.saveCurrent(name, saveAs = false)
        history.clear()
        EqBus.changed()
        return emptyResult()
    }

    private fun delete(id: String): JsonElement {
        if (presetOf(id) != null) badRequest("built-in profiles cannot be deleted")
        if (savedProfile(id) == null) notFound("profile $id")
        // Signature: delete(profileId: String); deleting the active profile disables the equalizer and clears the selection.
        EqualizerProfiles.delete(id)
        history.clear()
        EqBus.changed()
        return emptyResult()
    }

    private fun undoRedo(undo: Boolean): JsonElement {
        val current = EqualizerProfiles.activeProfile ?: return emptyResult()
        val target = (if (undo) history.undo(current) else history.redo(current)) ?: return emptyResult()
        if (target.id != current.id) {
            history.clear()
        } else {
            EqualizerProfiles.editCurrent(target)
        }
        EqBus.changed()
        return emptyResult()
    }

    private fun reset(): JsonElement =
        edit("reset") {
            // Signature: resetAllBands() (also neutralises tone and stereo); editTone is repeated so a custom profile
            // without a stored baseline is neutralised too.
            EqualizerProfiles.resetAllBands()
            EqualizerProfiles.editTone {
                it.copy(
                    bassDb = 0.0,
                    trebleDb = 0.0,
                    stereoWidth = 1.0,
                    crossfeedEnabled = false,
                    crossfeedPreset = com.metrolist.shared.equalizer.CrossfeedPreset.DEFAULT,
                )
            }
        }

    // --- files -----------------------------------------------------------------------------------------------------

    private suspend fun importFile(path: String): JsonElement {
        val file = File(path)
        if (!file.isFile) notFound("file not found: $path")
        val content =
            try {
                withContext(Dispatchers.IO) { file.readText() }
            } catch (error: java.io.IOException) {
                badRequest("cannot read file: ${error.message}")
            }
        // Signature: parseEqualizerText(content: String): Pair<Double, List<ParametricEqBand>>? (null = unusable).
        val (preamp, parsed) = parseEqualizerText(content) ?: badRequest("not an Equalizer APO / AutoEq ParametricEQ file")
        // Filters written as OFF are not part of the profile.
        val bands = parsed.filter { it.enabled }
        if (bands.isEmpty()) badRequest("no enabled filters in file")
        val name =
            file.name
                .substringBeforeLast('.')
                .removeSuffix(" ParametricEQ")
                .removeSuffix(" GraphicEQ")
                .ifBlank { file.name }
        // Signature: importProfile(profile: EqualizerProfile) assigns a fresh custom_ id, saves and activates it.
        EqualizerProfiles.importProfile(EqualizerProfile(id = "", name = name, bands = bands, preamp = preamp, source = "import", outputGainDb = 0.0))
        history.clear()
        EqBus.changed()
        return resultId()
    }

    private suspend fun exportFile(
        id: String,
        path: String,
    ): JsonElement {
        val profile =
            EqualizerProfiles.activeProfile?.takeIf { it.id == id } ?: savedProfile(id) ?: notFound("profile $id")
        // Signature: fun EqualizerProfile.toParametricEqText(): String.
        val text = profile.toParametricEqText()
        val file = File(path)
        try {
            withContext(Dispatchers.IO) {
                file.absoluteFile.parentFile?.mkdirs()
                file.writeText(text)
            }
        } catch (error: java.io.IOException) {
            badRequest("cannot write file: ${error.message}")
        }
        return emptyResult()
    }

    // --- AutoEq ----------------------------------------------------------------------------------------------------

    private fun variantName(entry: AutoEqEntry): String = "${entry.source}/${entry.rig ?: entry.form}"

    private suspend fun models() =
        try {
            // Signature: suspend fun AutoEqCatalog.models(): List<AutoEqModel> (AutoEqCatalog is an object).
            AutoEqCatalog.models()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ApiException("network", "AutoEq index unavailable: ${error.message}", retryable = true)
        }

    private suspend fun autoEqSearch(query: String): JsonElement {
        backendReady()
        // Signature: searchAutoEqModels(models: List<AutoEqModel>, query: String): List<AutoEqModel>.
        val found = searchAutoEqModels(models(), query).take(50)
        return buildJsonObject {
            put(
                "items",
                JsonArray(
                    found.map { model ->
                        buildJsonObject {
                            put("name", model.name)
                            put("source", model.entries.firstOrNull()?.source ?: "")
                            put("variants", JsonArray(model.entries.map { kotlinx.serialization.json.JsonPrimitive(variantName(it)) }))
                        }
                    },
                ),
            )
        }
    }

    private suspend fun autoEqApply(
        name: String,
        variant: String?,
    ): JsonElement {
        backendReady()
        val model =
            models().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: notFound("AutoEq model $name")
        val entry =
            if (variant == null) {
                model.entries.firstOrNull()
            } else {
                model.entries.firstOrNull { variantName(it) == variant } ?: notFound("AutoEq variant $variant")
            } ?: notFound("AutoEq model $name has no entries")
        val profile =
            try {
                // Signature: suspend fun AutoEqCatalog.load(entry: AutoEqEntry): EqualizerProfile.
                AutoEqCatalog.load(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (error: InvalidEqualizerProfileException) {
                badRequest("AutoEq profile could not be parsed")
            } catch (error: Exception) {
                throw ApiException("network", "AutoEq download failed: ${error.message}", retryable = true)
            }
        return lock.withLock {
            // Signature: add(profile: EqualizerProfile, enabled: Boolean = true) saves, stamps and activates it.
            EqualizerProfiles.add(profile)
            history.clear()
            EqBus.changed()
            resultId()
        }
    }

    // --- devices and response --------------------------------------------------------------------------------------

    private fun bindDevice(p: JsonObject): JsonElement {
        val deviceId = p.requireStr("deviceId")
        if (deviceId.isBlank()) badRequest("deviceId must not be blank")
        if (!p.containsKey("profileId")) badRequest("missing 'profileId' (use null to unbind)")
        val profileId = p.str("profileId")
        if (profileId != null && presetOf(profileId) == null && savedProfile(profileId) == null) notFound("profile $profileId")
        // Signature: setDeviceProfile(deviceKey: String, profileId: String?).
        EqualizerProfiles.setDeviceProfile(deviceId, profileId)
        EqBus.changed()
        return emptyResult()
    }

    private fun response(points: Int): JsonElement {
        val (hz, db) = EqResponse.compute(EqualizerProfiles.activeProfile, points)
        return buildJsonObject {
            put("hz", JsonArray(hz.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("db", JsonArray(db.map { kotlinx.serialization.json.JsonPrimitive(if (it.isFinite()) it else 0.0) }))
        }
    }

    // --- topic -----------------------------------------------------------------------------------------------------

    /** Snapshot first, then `eq.state` after every change; with params `{spectrum:true}` also `eq.spectrum` at ~30 Hz. */
    val eqTopic: Topic =
        topic { params, emit ->
            backendReady()
            val spectrum = params.bool("spectrum") == true
            coroutineScope {
                try {
                    if (spectrum) {
                        // Signature: AudioSpectrumMeter.addConsumer() / removeConsumer(); levels(): FloatArray runs the analysis.
                        AudioSpectrumMeter.addConsumer()
                        launch(Dispatchers.Default) {
                            while (isActive) {
                                val bytes = SpectrumQuantise.toBytes(AudioSpectrumMeter.levels(), AudioSpectrumMeter.frequencies)
                                emit("eq.spectrum", buildJsonObject { put("b64", Base64.getEncoder().encodeToString(bytes)) })
                                delay(SPECTRUM_PERIOD_MS)
                            }
                        }
                    }
                    var first = true
                    EqBus.version.collect {
                        val event = if (first) "eq.snapshot" else "eq.state"
                        first = false
                        emit(event, stateJson())
                    }
                } finally {
                    if (spectrum) AudioSpectrumMeter.removeConsumer()
                }
            }
        }

    private const val SPECTRUM_PERIOD_MS = 33L
}
