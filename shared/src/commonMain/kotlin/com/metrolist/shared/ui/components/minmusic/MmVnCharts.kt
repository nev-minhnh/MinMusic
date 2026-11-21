package com.metrolist.shared.ui.components.minmusic

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.artist_play_all
import com.metrolist.shared.generated.resources.charts
import com.metrolist.shared.generated.resources.mm_chart_tab_label
import com.metrolist.shared.generated.resources.mm_charts_tab_soundcloud
import com.metrolist.shared.generated.resources.see_all
import com.metrolist.shared.generated.resources.source_badge_nct
import com.metrolist.shared.generated.resources.source_badge_sc
import com.metrolist.shared.generated.resources.source_badge_zing
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceCapability
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

/**
 * One chart from a source, e.g. Zing's realtime chart, an NCT top list or SoundCloud's trending chart. [songs] may be
 * only the start of the chart (or empty for charts NCT loads on demand); a non-null [continuation] means
 * [MmVnCharts.loadMore] returns more.
 */
@Immutable
data class MmChartTab(
    val key: String,
    val source: SourceId,
    val title: String,
    val songs: List<SongItem>,
    val chartId: String? = null,
    val continuation: String? = null,
)

/**
 * Loads the charts of the enabled chart sources (Zing MP3, NhacCuaTui, SoundCloud) through [SourceRegistry.call], so a
 * disabled or degraded source is skipped and failures count towards its health. Results are cached for [CACHE_MS] and
 * shared by Home (charts shelf), Search (trending chips) and the full chart screen.
 */
object MmVnCharts {
    private const val CACHE_MS = 15 * 60 * 1000L
    private const val SECTIONS_PER_SOURCE = 2
    val chartSources = listOf(SourceId.ZING, SourceId.NCT, SourceId.SC)

    private val mutex = Mutex()
    private var cachedSources: List<SourceId> = emptyList()
    private var cachedAt = 0L
    private var cached: List<MmChartTab> = emptyList()

    /** Sources whose toggle is on, read from Settings so composition observes changes. */
    fun enabledSources(): List<SourceId> =
        buildList {
            if (Settings.shared.zingSourceEnabled) add(SourceId.ZING)
            if (Settings.shared.nctSourceEnabled) add(SourceId.NCT)
            if (Settings.shared.soundCloudSourceEnabled) add(SourceId.SC)
        }

    /** Every cached chart of [sources], including on-demand ones without songs yet. */
    fun cachedFor(sources: List<SourceId>): List<MmChartTab> = if (sources.isNotEmpty() && sources == cachedSources) cached else emptyList()

    /** The compact selection Home and Search show: the first [SECTIONS_PER_SOURCE] charts with songs per source. */
    fun homeTabs(tabs: List<MmChartTab>): List<MmChartTab> =
        tabs
            .filter { it.songs.isNotEmpty() }
            .groupBy { it.source }
            .values
            .flatMap { it.take(SECTIONS_PER_SOURCE) }

    suspend fun load(
        registry: SourceRegistry,
        sources: List<SourceId>,
        force: Boolean = false,
    ): List<MmChartTab> =
        mutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            if (!force && sources == cachedSources && cached.isNotEmpty() && now - cachedAt < CACHE_MS) return@withLock cached
            val tabs =
                sources.flatMap { source ->
                    val supportsCharts = registry.available(source)?.capabilities?.contains(SourceCapability.CHARTS) == true
                    if (!supportsCharts) return@flatMap emptyList()
                    val sections =
                        try {
                            registry.call(source) { it.charts() }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // A failing source only drops its own tabs; SourceRegistry records the failure.
                            emptyList()
                        }
                    sections.mapIndexedNotNull { index, section ->
                        val songs = section.items.filterIsInstance<SongItem>().distinctBy { it.id }
                        val pageable = section.id != null && section.continuation != null
                        if (songs.isEmpty() && !pageable) {
                            null
                        } else {
                            MmChartTab(
                                key = "${source.key}_$index",
                                source = source,
                                title = section.title,
                                songs = songs,
                                chartId = section.id,
                                continuation = section.continuation.takeIf { section.id != null },
                            )
                        }
                    }
                }
            if (tabs.isNotEmpty()) {
                cachedSources = sources
                cachedAt = now
                cached = tabs
            }
            tabs
        }

    /** The next songs of [tab] after [continuation], as returned by its source; may repeat songs already shown. */
    suspend fun loadMore(
        registry: SourceRegistry,
        tab: MmChartTab,
        continuation: String,
    ): Pair<List<SongItem>, String?> {
        val chartId = tab.chartId ?: return emptyList<SongItem>() to null
        val page = registry.call(tab.source) { it.chartPage(chartId, continuation) }
        return page.items.filterIsInstance<SongItem>() to page.continuation
    }

    fun registryOrNull(): SourceRegistry? = KoinPlatform.getKoinOrNull()?.getOrNull<SourceRegistry>()
}

/**
 * The Home selection of the enabled sources' charts, or an empty list while loading, when no such source is enabled,
 * or when they all fail. Never shows placeholder data.
 */
@Composable
fun rememberMmVnCharts(): State<List<MmChartTab>> {
    val sources = MmVnCharts.enabledSources()
    return produceState(initialValue = MmVnCharts.homeTabs(MmVnCharts.cachedFor(sources)), sources) {
        if (sources.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        val registry = MmVnCharts.registryOrNull() ?: return@produceState
        value = MmVnCharts.homeTabs(withContext(Dispatchers.IO) { MmVnCharts.load(registry, sources) })
    }
}

/** Loaded songs of one chart on the full chart screen. */
@Immutable
data class MmChartPage(
    val songs: List<SongItem>,
    val continuation: String?,
    val isLoadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
)

/**
 * State of the full chart screen: every chart of [sources] and, per chart, the songs loaded so far. Paging appends
 * the source's next page and drops songs already listed, so adapters that answer with the whole chart work too.
 */
@Stable
class MmChartsState(
    private val sources: List<SourceId>,
    private val scope: CoroutineScope,
) {
    var tabs by mutableStateOf(MmVnCharts.cachedFor(sources))
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    private val pages = mutableStateMapOf<String, MmChartPage>()

    fun page(tab: MmChartTab): MmChartPage = pages[tab.key] ?: MmChartPage(tab.songs, tab.continuation)

    fun load(force: Boolean = false) {
        if (isLoading || sources.isEmpty()) return
        val registry = MmVnCharts.registryOrNull() ?: return
        isLoading = true
        isRefreshing = force
        loadFailed = false
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { MmVnCharts.load(registry, sources, force) }
                if (force || loaded != tabs) pages.clear()
                tabs = loaded
                loadFailed = loaded.isEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                loadFailed = tabs.isEmpty()
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    fun loadMore(tab: MmChartTab) {
        val current = page(tab)
        val continuation = current.continuation ?: return
        if (current.isLoadingMore) return
        val registry = MmVnCharts.registryOrNull() ?: return
        pages[tab.key] = current.copy(isLoadingMore = true, loadMoreFailed = false)
        scope.launch {
            try {
                val (more, next) = withContext(Dispatchers.IO) { MmVnCharts.loadMore(registry, tab, continuation) }
                val known = current.songs.mapTo(HashSet()) { it.id }
                val added = more.filter { known.add(it.id) }
                // A page without new songs ends the chart, so a source that repeats itself cannot loop.
                pages[tab.key] = MmChartPage(current.songs + added, next.takeIf { added.isNotEmpty() })
            } catch (error: CancellationException) {
                pages[tab.key] = current
                throw error
            } catch (error: Exception) {
                pages[tab.key] = current.copy(isLoadingMore = false, loadMoreFailed = true)
            }
        }
    }
}

@Composable
fun rememberMmChartsState(
    sources: List<SourceId>,
    scope: CoroutineScope,
): MmChartsState = remember(sources, scope) { MmChartsState(sources, scope) }

@Composable
fun mmChartSourceLabel(source: SourceId): String =
    when (source) {
        SourceId.ZING -> stringResource(Res.string.source_badge_zing)
        SourceId.NCT -> stringResource(Res.string.source_badge_nct)
        SourceId.SC -> stringResource(Res.string.mm_charts_tab_soundcloud)
        else -> source.key
    }

@Composable
fun mmChartTabLabel(tab: MmChartTab): String {
    val source =
        when (tab.source) {
            SourceId.ZING -> stringResource(Res.string.source_badge_zing)
            SourceId.NCT -> stringResource(Res.string.source_badge_nct)
            SourceId.SC -> stringResource(Res.string.source_badge_sc)
            else -> null
        }
    return if (source != null) stringResource(Res.string.mm_chart_tab_label, source, tab.title) else tab.title
}

/** Number of ranked rows the Home charts shelf shows per tab. */
const val MM_CHART_ROWS = 5

/**
 * "Charts" shelf for Home: section header with a "See all" action (opens the selected chart in full when [onSeeAll]
 * is set, otherwise plays it), one chip per chart (with the source dot) and the top [MM_CHART_ROWS] songs as ranked
 * rows. Rendered as a single lazy item, so it never reflows the list.
 */
@Composable
fun MmChartsShelf(
    tabs: List<MmChartTab>,
    onSongClick: (songs: List<SongItem>, index: Int) -> Unit,
    onSongMenu: (SongItem) -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    modifier: Modifier = Modifier,
    onSeeAll: ((MmChartTab) -> Unit)? = null,
) {
    if (tabs.isEmpty()) return
    var selectedKey by rememberSaveable { mutableStateOf(tabs.first().key) }
    val selected = tabs.firstOrNull { it.key == selectedKey } ?: tabs.first()
    val rows = remember(selected) { selected.songs.take(MM_CHART_ROWS) }
    Column(modifier.fillMaxWidth()) {
        MmSectionHeader(
            title = stringResource(Res.string.charts),
            actionLabel = stringResource(if (onSeeAll != null) Res.string.see_all else Res.string.artist_play_all),
            onAction = { if (onSeeAll != null) onSeeAll(selected) else onPlayAll(selected.songs) },
            bottomPadding = 8.dp,
        )
        if (tabs.size > 1) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEach { tab ->
                    MmChip(
                        text = mmChartTabLabel(tab),
                        selected = tab.key == selected.key,
                        onClick = { selectedKey = tab.key },
                        dotColor = MmSourceColors.of(tab.source),
                    )
                }
            }
        }
        val currentId = currentSongId.value
        val playing = isPlaying.value
        rows.forEachIndexed { index, song ->
            val isCurrent = song.id == currentId
            MmTrackRow(
                song = song,
                rank = index + 1,
                isCurrent = isCurrent,
                isPlaying = isCurrent && playing,
                onClick = { onSongClick(selected.songs, index) },
                onShowMenu = { onSongMenu(song) },
            )
        }
    }
}
