package com.metrolist.core.api.browse

import com.metrolist.core.api.jsonOf
import com.metrolist.core.api.toShelvesJson
import com.metrolist.core.api.toShelves
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.source.MusicSource
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import com.metrolist.shared.source.SourceUnavailableException
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.minmusic.MmVnCharts
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext

/** api.md 3.2: `home.more`, `charts.get`, `browse.page`, `moods.list` (`home.get` lives in HomeApi). */
object BrowseApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "home.more" to handler { params, _ -> homeMore(params) },
            "charts.get" to handler { params, _ -> charts(params) },
            "browse.page" to handler { params, _ -> browsePage(params) },
            "moods.list" to handler { _, _ -> moods() },
        )

    private fun cursorOrNull(token: String?): JsonElement = token?.let { JsonPrimitive(it) } ?: JsonNull

    // --- home.more -----------------------------------------------------------------------------------------------

    private suspend fun homeMore(params: JsonObject): JsonElement {
        backendReady()
        val (token, personal) = BrowseCursor.unwrap(params.requireStr("cursor"))
        // getHomeSectionsContinuation(token: String, isPersonal: Boolean = false, loadAllPages: Boolean = true): Pair<List<HomeSection>, String?>
        val (sections, next) = GlobalYouTubeRepository.instance.getHomeSectionsContinuation(token, isPersonal = personal, loadAllPages = false)
        return buildJsonObject {
            put("shelves", sections.toShelves().toShelvesJson())
            put("next", cursorOrNull(BrowseCursor.wrap(next, personal)))
        }
    }

    // --- browse.page ---------------------------------------------------------------------------------------------

    private suspend fun browsePage(params: JsonObject): JsonElement {
        backendReady()
        val browseId = params.requireStr("browseId")
        // getBrowsePageData(browseId, params, title, loadAllPages): BrowsePageData(title, sections, continuation)
        val data = GlobalYouTubeRepository.instance.getBrowsePageData(browseId, params.str("params"), null, loadAllPages = false)
        return buildJsonObject {
            put("title", data.title.orEmpty())
            put("shelves", data.sections.toShelves().toShelvesJson())
            put("next", cursorOrNull(BrowseCursor.wrap(data.continuation, BrowseCursor.usesLogin(browseId))))
        }
    }

    // --- moods.list ----------------------------------------------------------------------------------------------

    private suspend fun moods(): JsonElement {
        backendReady()
        val repo = GlobalYouTubeRepository.instance
        // getBrowsePageData(browseId, params, title, loadAllPages): BrowsePageData(title, sections, continuation)
        val fromPage =
            try {
                repo.getBrowsePageData("FEmusic_moods_and_genres", loadAllPages = false).sections.flatMap { it.items }.filterIsInstance<BrowseItem>()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        val items =
            fromPage.ifEmpty {
                // The moods page is empty or refused without a signed-in session: the Home page carries the same mood chips.
                // getHomePageData(params: String? = null, browseId: String = "FEmusic_home", setLogin: Boolean): HomePageData(chips, sections, continuation)
                repo.getHomePageData().chips.map { chip ->
                    BrowseItem(id = chip.browseId ?: "FEmusic_home", title = chip.title, params = chip.params)
                }
            }
        return buildJsonObject { put("items", JsonArray(moodItems(items))) }
    }

    /** `{key: "<id>|<params>", title}`, unique by key and skipping untitled entries; pure. */
    fun moodItems(items: List<BrowseItem>): List<JsonObject> =
        items
            .filter { it.title.isNotBlank() }
            .map { item -> "${item.id}|${item.params ?: ""}" to item.title }
            .distinctBy { it.first }
            .map { (key, title) -> jsonOf("key" to JsonPrimitive(key), "title" to JsonPrimitive(title)) }

    // --- charts.get ----------------------------------------------------------------------------------------------

    private suspend fun charts(params: JsonObject): JsonElement {
        backendReady()
        val source = params.requireStr("source")
        val key = params.str("key")
        return when (source) {
            "zing" -> vnChart(SourceId.ZING, key)
            "nct" -> vnChart(SourceId.NCT, key)
            "yt" -> youtubeChart()
            else -> throw ApiException("bad_request", "source must be zing, nct or yt")
        }
    }

    private suspend fun vnChart(
        source: SourceId,
        key: String?,
    ): JsonElement =
        withSourceErrors {
            val shared = sourceRegistry()
            // Charts are public on both sources, so a source the user has not switched on still serves them (api.md 3.2).
            val registry = if (shared.isEnabled(source)) shared else chartRegistry(source) ?: throw sourceUnavailable(source, SourceUnavailableException.Reason.DISABLED)
            // MmVnCharts.load skips a source that is not available and returns no tabs, so report the reason ourselves.
            if (registry.available(source) == null) throw sourceUnavailable(source, SourceUnavailableException.Reason.DEGRADED)
            val tabs = MmVnCharts.load(registry, listOf(source))
            if (tabs.isEmpty()) throw sourceUnavailable(source, SourceUnavailableException.Reason.DEGRADED)
            val tab = key?.let { wanted -> tabs.firstOrNull { it.key == wanted } } ?: tabs.first()
            var songs: List<SongItem> = tab.songs
            if (tab.continuation != null) {
                // MmVnCharts.loadMore(registry, tab, continuation): Pair<List<SongItem>, String?>; one extra page, "full" per the brief.
                val more =
                    try {
                        MmVnCharts.loadMore(registry, tab, "full").first
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                songs = songs + more
            }
            chartResult(source.key, tab.title, tabs.map { it.key to it.title }, songs)
        }

    /**
     * A private registry holding only [source], always switched on. It carries no failure history of its own, so a
     * disabled source never turns "degraded" in the shared registry. `getAll<MusicSource>()` is the same lookup
     * SharedModule.kt uses to build the shared registry.
     */
    private fun chartRegistry(source: SourceId): SourceRegistry? {
        val musicSource = GlobalContext.get().getAll<MusicSource>().firstOrNull { it.id == source } ?: return null
        return SourceRegistry(listOf(musicSource), enabledSetting = { true })
    }

    private suspend fun youtubeChart(): JsonElement {
        val data = GlobalYouTubeRepository.instance.getBrowsePageData("FEmusic_charts", loadAllPages = false)
        val section = data.sections.firstOrNull { it.items.isNotEmpty() && it.items.all { item -> item is SongItem } }
        val songs = section?.items?.filterIsInstance<SongItem>().orEmpty()
        val title = section?.title ?: data.title.orEmpty()
        return chartResult("yt", title, listOf("yt" to title), songs)
    }

    /** `{source, title, updatedAt, tabs, items}` of api.md 3.2; pure apart from the clock. */
    fun chartResult(
        source: String,
        title: String,
        tabs: List<Pair<String, String>>,
        songs: List<SongItem>,
        now: Long = System.currentTimeMillis(),
    ): JsonObject =
        buildJsonObject {
            put("source", source)
            put("title", title)
            put("updatedAt", now)
            put("tabs", JsonArray(tabs.map { (tabKey, tabTitle) -> jsonOf("key" to JsonPrimitive(tabKey), "title" to JsonPrimitive(tabTitle)) }))
            put("items", ChartMapper.items(songs))
        }
}
