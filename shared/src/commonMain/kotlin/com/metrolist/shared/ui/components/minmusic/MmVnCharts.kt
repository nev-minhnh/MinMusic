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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import com.metrolist.shared.generated.resources.source_badge_nct
import com.metrolist.shared.generated.resources.source_badge_zing
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceCapability
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

/** One chart from a Vietnamese source, e.g. Zing's realtime chart or an NCT top list. */
@Immutable
data class MmChartTab(
    val key: String,
    val source: SourceId,
    val title: String,
    val songs: List<SongItem>,
)

/**
 * Loads the charts of the enabled Vietnamese sources (Zing MP3, NhacCuaTui) through [SourceRegistry.call], so a
 * disabled or degraded source is skipped and failures count towards its health. Results are cached for
 * [CACHE_MS] and shared by Home (charts shelf) and Search (trending chips).
 */
object MmVnCharts {
    private const val CACHE_MS = 15 * 60 * 1000L
    private const val SECTIONS_PER_SOURCE = 2
    val chartSources = listOf(SourceId.ZING, SourceId.NCT)

    private val mutex = Mutex()
    private var cachedSources: List<SourceId> = emptyList()
    private var cachedAt = 0L
    private var cached: List<MmChartTab> = emptyList()

    /** Sources whose chart toggle is on, read from Settings so composition observes changes. */
    fun enabledSources(): List<SourceId> =
        buildList {
            if (Settings.shared.zingSourceEnabled) add(SourceId.ZING)
            if (Settings.shared.nctSourceEnabled) add(SourceId.NCT)
        }

    fun cachedFor(sources: List<SourceId>): List<MmChartTab> = if (sources.isNotEmpty() && sources == cachedSources) cached else emptyList()

    suspend fun load(
        registry: SourceRegistry,
        sources: List<SourceId>,
    ): List<MmChartTab> =
        mutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            if (sources == cachedSources && cached.isNotEmpty() && now - cachedAt < CACHE_MS) return@withLock cached
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
                    sections
                        .take(SECTIONS_PER_SOURCE)
                        .mapIndexedNotNull { index, section ->
                            val songs = section.items.filterIsInstance<SongItem>()
                            if (songs.isEmpty()) null else MmChartTab("${source.key}_$index", source, section.title, songs)
                        }
                }
            if (tabs.isNotEmpty()) {
                cachedSources = sources
                cachedAt = now
                cached = tabs
            }
            tabs
        }
}

/**
 * The charts of the enabled Vietnamese sources, or an empty list while loading, when no such source is enabled, or
 * when they all fail. Never shows placeholder data.
 */
@Composable
fun rememberMmVnCharts(): State<List<MmChartTab>> {
    val sources = MmVnCharts.enabledSources()
    return produceState(initialValue = MmVnCharts.cachedFor(sources), sources) {
        if (sources.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        val registry = KoinPlatform.getKoinOrNull()?.getOrNull<SourceRegistry>() ?: return@produceState
        value = withContext(Dispatchers.IO) { MmVnCharts.load(registry, sources) }
    }
}

@Composable
fun mmChartTabLabel(tab: MmChartTab): String {
    val source =
        when (tab.source) {
            SourceId.ZING -> stringResource(Res.string.source_badge_zing)
            SourceId.NCT -> stringResource(Res.string.source_badge_nct)
            else -> null
        }
    return if (source != null) stringResource(Res.string.mm_chart_tab_label, source, tab.title) else tab.title
}

/** Number of ranked rows the Home charts shelf shows per tab. */
const val MM_CHART_ROWS = 5

/**
 * "Charts" shelf for Home: section header with a play-all action, one chip per chart (with the source dot) and the
 * top [MM_CHART_ROWS] songs as ranked rows. Rendered as a single lazy item, so it never reflows the list.
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
) {
    if (tabs.isEmpty()) return
    var selectedKey by rememberSaveable { mutableStateOf(tabs.first().key) }
    val selected = tabs.firstOrNull { it.key == selectedKey } ?: tabs.first()
    val rows = remember(selected) { selected.songs.take(MM_CHART_ROWS) }
    Column(modifier.fillMaxWidth()) {
        MmSectionHeader(
            title = stringResource(Res.string.charts),
            actionLabel = stringResource(Res.string.artist_play_all),
            onAction = { onPlayAll(selected.songs) },
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
