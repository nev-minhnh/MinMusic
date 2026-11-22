package com.metrolist.core.api.search

import com.metrolist.core.api.browse.sourceRegistry
import com.metrolist.core.api.browse.withSourceErrors
import com.metrolist.core.api.jsonOf
import com.metrolist.core.api.toItems
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Conn
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.SearchHistoryCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** api.md 3.3. */
object SearchApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "search.suggest" to handler { params, conn -> suggest(params, conn) },
            "search.run" to handler { params, _ -> run(params) },
            "search.history.list" to handler { _, _ -> historyList() },
            "search.history.remove" to handler { params, _ -> historyRemove(params) },
        )

    private const val SUGGEST_ROWS = 8

    /** Latest in-flight suggest call per connection; a newer call cancels the older one. */
    private val inflightSuggest = ConcurrentHashMap<Conn, Job>()

    // --- search.suggest ------------------------------------------------------------------------------------------

    private suspend fun suggest(
        params: JsonObject,
        conn: Conn,
    ): JsonElement {
        backendReady()
        val q = params.str("q").orEmpty().trim()
        return coroutineScope {
            val call: Deferred<JsonElement> = async { fetchSuggestions(q) }
            inflightSuggest.put(conn, call)?.cancel()
            try {
                call.await()
            } catch (error: CancellationException) {
                // The call itself was cancelled by a newer one while this request is still alive: tell the client.
                if (isActive) throw ApiException("cancelled", "superseded by a newer search.suggest")
                throw error
            } finally {
                inflightSuggest.remove(conn, call)
            }
        }
    }

    private suspend fun fetchSuggestions(q: String): JsonElement {
        if (q.isEmpty()) {
            return buildJsonObject {
                put("queries", JsonArray(emptyList()))
                put("items", JsonArray(emptyList()))
            }
        }
        // getSearchSuggestions(query: String): SearchSuggestions?  (queries, recommendedItems)
        val suggestions = GlobalYouTubeRepository.instance.getSearchSuggestions(q)
        val queries = suggestions?.queries.orEmpty().take(SUGGEST_ROWS)
        val items = suggestions?.recommendedItems.orEmpty().toItems().take(SUGGEST_ROWS - queries.size)
        return buildJsonObject {
            put("queries", JsonArray(queries.map { JsonPrimitive(it) }))
            put("items", JsonArray(items))
        }
    }

    // --- search.run ----------------------------------------------------------------------------------------------

    private suspend fun run(params: JsonObject): JsonElement {
        backendReady()
        val q = params.requireStr("q").trim()
        if (q.isEmpty()) badRequest("empty query")
        val source = params.str("source") ?: "all"
        val type = params.str("type") ?: "all"
        val cursor = params.str("cursor")
        val filter = if (type == "all") null else SearchMapper.filterOf(type) ?: badRequest("unknown type '$type'")
        return when (source) {
            "yt" -> ytSearch(q, type, filter, cursor)
            "sc" -> sourceSearch(SourceId.SC, q, type, filter, cursor)
            "zing" -> sourceSearch(SourceId.ZING, q, type, filter, cursor)
            "nct" -> sourceSearch(SourceId.NCT, q, type, filter, cursor)
            "all" -> allSearch(q, type, filter)
            else -> badRequest("unknown source '$source'")
        }
    }

    private fun result(
        top: JsonObject?,
        sections: List<JsonObject>,
        next: String?,
    ): JsonObject =
        jsonOf(
            "top" to top,
            "sections" to JsonArray(sections),
            "next" to (next?.let { JsonPrimitive(it) } ?: JsonNull),
        )

    private suspend fun ytSearch(
        q: String,
        type: String,
        filter: SearchFilter?,
        cursor: String?,
    ): JsonObject {
        val repo = GlobalYouTubeRepository.instance
        if (filter == null) {
            // getSearchSummary(query, recordSearchHistory = false): SearchSummaryPage(summaries)
            val (top, sections) = SearchMapper.summaryResult(repo.getSearchSummary(q).summaries)
            return result(top, sections, null)
        }
        // searchFiltered(query, filter, continuation, ...): SearchResultPage(items, continuation)
        val page = repo.searchFiltered(q, filter, cursor)
        val section = SearchMapper.section(type, SearchMapper.titleOf(type), page.items)
        return result(null, listOfNotNull(section), page.continuation)
    }

    private suspend fun sourceSearch(
        source: SourceId,
        q: String,
        type: String,
        filter: SearchFilter?,
        cursor: String?,
    ): JsonObject {
        val page = SearchCursor.parse(cursor)?.takeIf { it.first == source }?.second ?: 0
        val sectionType = if (type == "all") "track" else type
        val found =
            withSourceErrors {
                // MusicSource.search(query, filter, page: Int): SourcePage<YTItem>(items, continuation); page is zero-based.
                sourceRegistry().call(source) { it.search(q, filter ?: SearchFilter.SONG, page) }
            }
        val section = SearchMapper.section(sectionType, SearchMapper.titleOf(sectionType), found.items)
        val next = found.continuation?.let { SearchCursor.encode(source, it.toIntOrNull() ?: (page + 1)) }
        return result(null, listOfNotNull(section), next)
    }

    /** YouTube plus one songs section per enabled source, in parallel; a failing source is dropped, no `next`. */
    private suspend fun allSearch(
        q: String,
        type: String,
        filter: SearchFilter?,
    ): JsonObject =
        supervisorScope {
            val registry = sourceRegistry()
            val yt = async { ytSearch(q, type, filter, null) }
            val extras =
                if (type == "all" || type == "track") {
                    listOf(SourceId.ZING, SourceId.NCT, SourceId.SC)
                        .filter { registry.available(it) != null }
                        .map { source ->
                            source to
                                async {
                                    try {
                                        registry.call(source) { it.search(q, SearchFilter.SONG, 0) }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                        }
                } else {
                    emptyList()
                }
            val base = yt.await()
            val sourceSections =
                extras.mapNotNull { (source, deferred) ->
                    val page = deferred.await() ?: return@mapNotNull null
                    SearchMapper.section(
                        "track",
                        SearchMapper.sourceLabel(source),
                        page.items,
                        SearchMapper.searchNav(q, source, "track"),
                    )
                }
            val ytSections = (base["sections"] as? JsonArray)?.map { it as JsonObject }.orEmpty()
            result(base["top"] as? JsonObject, ytSections + sourceSections, null)
        }

    // --- search.history.* ----------------------------------------------------------------------------------------

    private suspend fun historyList(): JsonElement {
        backendReady()
        val items = withContext(Dispatchers.IO) { SearchHistoryCodec.decode(Settings.shared.searchHistory) }
        return buildJsonObject { put("items", JsonArray(items.map { JsonPrimitive(it) })) }
    }

    private suspend fun historyRemove(params: JsonObject): JsonElement {
        backendReady()
        val q = params.str("q")
        withContext(Dispatchers.IO) {
            val remaining = SearchMapper.removeFromHistory(SearchHistoryCodec.decode(Settings.shared.searchHistory), q)
            Settings.shared.searchHistory = if (remaining.isEmpty()) "" else SearchHistoryCodec.encode(remaining)
        }
        return emptyResult()
    }
}
