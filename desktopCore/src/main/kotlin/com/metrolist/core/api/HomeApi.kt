package com.metrolist.core.api

import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreConfig
import com.metrolist.core.bridge.AppEvents
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.bool
import com.metrolist.core.server.handler
import com.metrolist.core.server.str
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.viewmodels.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import java.io.File
import java.time.LocalTime

/**
 * api.md 3.2 `home.get` (`home.more` is in BrowseApi). With `--fixture-home <file-or-dir>` the call short-circuits to the fixture (a directory means
 * `<dir>/home.json`) and never touches the network or the database. Otherwise it maps the v1 HomeViewModel state.
 */
object HomeApi {
    val handlers: Map<String, Handler> = mapOf("home.get" to handler { params, _ -> get(params) })

    private val fixture: JsonElement? by lazy {
        val path = CoreConfig.options.fixtureHome ?: return@lazy null
        val file = File(path).let { if (it.isDirectory) File(it, "home.json") else it }
        if (!file.isFile) return@lazy null
        runCatching { Json.parseToJsonElement(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private val viewModel: HomeViewModel by lazy {
        HomeViewModel(
            database = GlobalContext.get().get<MusicDatabase>(),
            scope = CoreBackend.scope,
            likedSongsName = "Liked Songs",
        )
    }

    private fun greetingKey(): String =
        when (LocalTime.now().hour) {
            in 5..11 -> "good_morning"
            in 12..17 -> "good_afternoon"
            in 18..22 -> "good_evening"
            else -> "good_night"
        }

    private fun chipKeyOf(chip: Chip): String = chip.params ?: chip.browseId ?: chip.title

    /**
     * The fixture as `home.get` answers it: with a `chip` param exactly that chip is marked `selected` (the others are
     * cleared), without one the fixture is returned as written. A key that matches no chip leaves the chips unchanged. Pure.
     */
    internal fun selectFixtureChip(
        fixture: JsonElement,
        chipKey: String?,
    ): JsonElement {
        val root = fixture as? JsonObject ?: return fixture
        val chips = root["chips"] as? JsonArray ?: return fixture
        if (chipKey == null || chips.none { (it as? JsonObject)?.get("key")?.let { k -> (k as? JsonPrimitive)?.content } == chipKey }) return fixture
        val marked =
            chips.map { chip ->
                val obj = chip as? JsonObject ?: return@map chip
                val key = (obj["key"] as? JsonPrimitive)?.content
                JsonObject(obj + ("selected" to JsonPrimitive(key == chipKey)))
            }
        return JsonObject(root + ("chips" to JsonArray(marked)))
    }

    /** Shelves of a list of home sections as wire JSON; pure. */
    internal fun shelvesJson(sections: List<HomeSection>): JsonArray = sections.toShelves().toShelvesJson()

    @Volatile
    private var updateWatcher: Job? = null

    suspend fun get(params: JsonObject): JsonElement {
        fixture?.let { return selectFixtureChip(it, params.str("chip")) }
        backendReady()
        return withContext(Dispatchers.IO) {
            val model = viewModel
            if (params.bool("refresh") == true) model.refresh {}
            val chipKey = params.str("chip")
            if (chipKey != null) selectChip(model, chipKey)
            // Return what the database/cache has right away; wait briefly for a first non-empty result on a cold start.
            val state = withTimeoutOrNull(FIRST_RESULT_WAIT_MS) { model.uiState.first { !it.data.isNullOrEmpty() } } ?: model.uiState.value
            val sections = state.data.orEmpty()
            val shelves = shelvesJson(sections)
            val fromCache = state.isLoading || state.isRefreshing || state.isRestoringCache || state.isRevalidating
            if (fromCache) watchForUpdate(model, shelves)
            buildJsonObject {
                put("shelves", shelves)
                put(
                    "chips",
                    JsonArray(
                        state.chips.map { chip ->
                            val key = chipKeyOf(chip)
                            buildJsonObject {
                                put("key", key)
                                put("title", chip.title)
                                put("selected", key == chipKey || state.selectedChip == chip)
                            }
                        },
                    ),
                )
                put("greeting", buildJsonObject { put("key", greetingKey()) })
                put("fromCache", fromCache)
                put("next", state.continuation?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
    }

    /**
     * Selects the chip with [key] (matched on `params ?: browseId ?: title`) through `toggleChip` on core-main, then waits
     * up to [CHIP_WAIT_MS] for its content. A chip that is already selected is left alone: toggling it again would clear it.
     */
    private suspend fun selectChip(
        model: HomeViewModel,
        key: String,
    ) {
        val chip = model.uiState.value.chips.firstOrNull { chipKeyOf(it) == key } ?: return
        if (model.uiState.value.selectedChip != chip) {
            // toggleChip(chip: Chip?) sets selectedChip synchronously, so the wait below sees the new selection.
            withContext(Dispatchers.Main) { model.toggleChip(chip) }
        }
        withTimeoutOrNull(CHIP_WAIT_MS) { model.uiState.first { it.selectedChip == chip && !it.isLoading && !it.isContentLoading } }
    }

    /** Pushes `home.updated {shelves}` when the network result that replaces a cached answer differs from it. */
    private fun watchForUpdate(
        model: HomeViewModel,
        returned: JsonArray,
    ) {
        updateWatcher?.cancel()
        updateWatcher =
            CoreBackend.scope.launch {
                val settled =
                    withTimeoutOrNull(UPDATE_WAIT_MS) {
                        model.uiState.first {
                            !it.isLoading && !it.isRefreshing && !it.isRestoringCache && !it.isRevalidating && !it.data.isNullOrEmpty()
                        }
                    } ?: return@launch
                val latest = shelvesJson(settled.data.orEmpty())
                if (latest != returned) AppEvents.emit("home.updated", buildJsonObject { put("shelves", latest) })
            }
    }

    private const val FIRST_RESULT_WAIT_MS = 2_500L
    private const val CHIP_WAIT_MS = 8_000L
    private const val UPDATE_WAIT_MS = 30_000L
}
