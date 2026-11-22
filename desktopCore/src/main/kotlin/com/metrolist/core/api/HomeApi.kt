package com.metrolist.core.api

import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreConfig
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.bool
import com.metrolist.core.server.handler
import com.metrolist.core.server.str
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.viewmodels.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import java.io.File
import java.time.LocalTime

/**
 * api.md 3.2 `home.get`. With `--fixture-home <file-or-dir>` the call short-circuits to the fixture (a directory means
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

    suspend fun get(params: JsonObject): JsonElement {
        fixture?.let { return it }
        backendReady()
        return withContext(Dispatchers.IO) {
            val model = viewModel
            if (params.bool("refresh") == true) model.refresh {}
            // Return what the database/cache has right away; wait briefly for a first non-empty result on a cold start.
            val state = withTimeoutOrNull(FIRST_RESULT_WAIT_MS) { model.uiState.first { !it.data.isNullOrEmpty() } } ?: model.uiState.value
            val chipKey = params.str("chip")
            val sections = state.data.orEmpty()
            buildJsonObject {
                put(
                    "shelves",
                    JsonArray(
                        sections.mapIndexed { index, section ->
                            Shelf(
                                key = section.browseId ?: "section-$index",
                                title = section.title,
                                subtitle = section.subtitle,
                                layout = if (section.isListStyle) "rows" else "cards",
                                items = section.items.mapNotNull { it.toItem() },
                            ).toJson()
                        },
                    ),
                )
                put(
                    "chips",
                    JsonArray(
                        state.chips.map { chip ->
                            val key = chip.params ?: chip.browseId ?: chip.title
                            buildJsonObject {
                                put("key", key)
                                put("title", chip.title)
                                put("selected", key == chipKey || state.selectedChip == chip)
                            }
                        },
                    ),
                )
                put("greeting", buildJsonObject { put("key", greetingKey()) })
                put("fromCache", state.isLoading || state.isRefreshing || state.isRestoringCache)
                put("next", state.continuation?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            }
        }
    }

    private const val FIRST_RESULT_WAIT_MS = 2_500L
}
