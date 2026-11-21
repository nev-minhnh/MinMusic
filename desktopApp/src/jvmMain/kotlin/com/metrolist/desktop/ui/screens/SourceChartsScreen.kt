/*
 * MinMusic desktop: full charts of Zing MP3, NhacCuaTui and SoundCloud (Android SourceChartsScreen counterpart).
 */

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.components.SongContextMenu
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.artist_play_all
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.charts
import com.metrolist.shared.generated.resources.mm_charts_empty
import com.metrolist.shared.generated.resources.mm_charts_error
import com.metrolist.shared.generated.resources.mm_charts_load_more_failed
import com.metrolist.shared.generated.resources.mm_charts_no_sources
import com.metrolist.shared.generated.resources.mm_charts_song_count
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.PlaylistHeaderActions
import com.metrolist.shared.ui.components.minmusic.MmChartPage
import com.metrolist.shared.ui.components.minmusic.MmChartTab
import com.metrolist.shared.ui.components.minmusic.MmChip
import com.metrolist.shared.ui.components.minmusic.MmSourceColors
import com.metrolist.shared.ui.components.minmusic.MmTrackRow
import com.metrolist.shared.ui.components.minmusic.MmVnCharts
import com.metrolist.shared.ui.components.minmusic.mmChartSourceLabel
import com.metrolist.shared.ui.components.minmusic.rememberMmChartsState
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.contextMenuAnchor
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource

/** Rows left below the viewport when the next chart page starts loading. */
private const val LOAD_MORE_THRESHOLD = 8

/**
 * The complete charts of the enabled chart sources: source chips, a chip per chart when a source has several, and
 * every song of the selected chart as ranked rows in one readable column (it does not stretch on wide windows).
 */
@Composable
fun SourceChartsScreen(
    initialSourceKey: String?,
    initialChartKey: String?,
) {
    val sources = MmVnCharts.enabledSources()
    val scope = rememberCoroutineScope()
    val state = rememberMmChartsState(sources, scope)
    LaunchedEffect(state) { state.load() }
    val contextMenu = rememberCursorContextMenuState()
    var selectedMenuSong by remember { mutableStateOf<SongItem?>(null) }

    val tabs = state.tabs
    var selectedKey by rememberSaveable { mutableStateOf(initialChartKey) }
    val selected =
        tabs.firstOrNull { it.key == selectedKey }
            ?: tabs.firstOrNull { it.source.key == initialSourceKey }
            ?: tabs.firstOrNull()
    val chartSources = remember(tabs) { tabs.map { it.source }.distinct() }
    val sourceCharts = remember(tabs, selected?.source) { tabs.filter { it.source == selected?.source } }
    val page = selected?.let { state.page(it) }

    val listState = rememberLazyListState()
    var shownKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selected?.key, tabs) {
        val tab = selected ?: return@LaunchedEffect
        if (shownKey != null && shownKey != tab.key) listState.scrollToItem(0)
        shownKey = tab.key
        state.loadMore(tab)
    }
    LaunchedEffect(listState, selected?.key) {
        val tab = selected ?: return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val nearEnd = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_THRESHOLD
            nearEnd to info.totalItemsCount
        }.distinctUntilChanged()
            .collect { (nearEnd, _) -> if (nearEnd) state.loadMore(tab) }
    }

    Box(
        modifier = Modifier.fillMaxSize().contextMenuAnchor(contextMenu),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { DesktopNavigation.goBack() }) {
                    Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                }
                Text(
                    text = stringResource(Res.string.charts),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconButton(onClick = { state.load(force = true) }, enabled = !state.isLoading && sources.isNotEmpty()) {
                    Icon(MetrolistIcons.Outlined.Refresh, stringResource(Res.string.refresh))
                }
            }
            if (chartSources.size > 1) {
                ChipRow {
                    chartSources.forEach { source ->
                        MmChip(
                            text = mmChartSourceLabel(source),
                            selected = source == selected?.source,
                            onClick = { selectedKey = tabs.firstOrNull { it.source == source }?.key },
                            dotColor = MmSourceColors.of(source),
                        )
                    }
                }
            }
            if (sourceCharts.size > 1) {
                ChipRow {
                    sourceCharts.forEach { tab ->
                        MmChip(
                            text = tab.title,
                            selected = tab.key == selected?.key,
                            onClick = { selectedKey = tab.key },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    sources.isEmpty() -> {
                        ChartMessage(stringResource(Res.string.mm_charts_no_sources))
                    }

                    selected == null && state.isLoading -> {
                        ChartLoading()
                    }

                    selected == null -> {
                        ChartMessage(stringResource(Res.string.mm_charts_error), onRetry = { state.load(force = true) })
                    }

                    page == null || (page.songs.isEmpty() && page.continuation != null && !page.loadMoreFailed) -> {
                        ChartLoading()
                    }

                    page.songs.isEmpty() && page.loadMoreFailed -> {
                        ChartMessage(stringResource(Res.string.mm_charts_error), onRetry = { state.loadMore(selected) })
                    }

                    page.songs.isEmpty() -> {
                        ChartMessage(stringResource(Res.string.mm_charts_empty))
                    }

                    else -> {
                        ChartList(
                            tab = selected,
                            page = page,
                            listState = listState,
                            currentSongId = PlayerState.currentTrack?.id,
                            isPlaying = PlayerState.isPlaying,
                            onPlay = { songs, index -> songs.getOrNull(index)?.let { PlayerState.playTrack(it, songs) } },
                            onShuffle = { songs -> PlayerState.shuffleQueue(songs) },
                            onSongMenu = { song ->
                                selectedMenuSong = song
                                contextMenu.open()
                            },
                            onLoadMoreRetry = { state.loadMore(selected) },
                        )
                    }
                }
            }
        }
        selectedMenuSong?.let { song ->
            SongContextMenu(
                song = song,
                expanded = contextMenu.expanded,
                offset = contextMenu.offset,
                onDismiss = contextMenu::dismiss,
            )
        }
    }
}

@Composable
private fun ChartList(
    tab: MmChartTab,
    page: MmChartPage,
    listState: LazyListState,
    currentSongId: String?,
    isPlaying: Boolean,
    onPlay: (List<SongItem>, Int) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    onSongMenu: (SongItem) -> Unit,
    onLoadMoreRetry: () -> Unit,
) {
    val songs = page.songs
    val rankWidth = if (songs.size >= 100) 40.dp else 32.dp
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "chart_actions", contentType = "chart_actions") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(Res.string.mm_charts_song_count, songs.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.widthIn(max = 420.dp)) {
                    PlaylistHeaderActions(
                        onPlayAll = { onPlay(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                        playLabel = stringResource(Res.string.artist_play_all),
                        shuffleLabel = stringResource(Res.string.shuffle),
                        showPlayback = true,
                        isFavorite = false,
                        onFavorite = null,
                        buttonHeight = 48.dp,
                    )
                }
            }
        }
        itemsIndexed(
            items = songs,
            key = { _, song -> "${tab.key}:${song.id}" },
            contentType = { _, _ -> "chart_song" },
        ) { index, song ->
            val isCurrent = song.id == currentSongId
            MmTrackRow(
                song = song,
                rank = index + 1,
                rankWidth = rankWidth,
                isCurrent = isCurrent,
                isPlaying = isCurrent && isPlaying,
                onClick = { onPlay(songs, index) },
                onShowMenu = { onSongMenu(song) },
            )
        }
        if (page.isLoadingMore || page.loadMoreFailed) {
            item(key = "chart_footer", contentType = "chart_footer") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (page.isLoadingMore) {
                        ContainedLoadingIndicator(Modifier.size(40.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(Res.string.mm_charts_load_more_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onLoadMoreRetry) { Text(stringResource(Res.string.retry)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun ChartLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ContainedLoadingIndicator()
    }
}

@Composable
private fun ChartMessage(
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(Res.string.retry)) }
        }
    }
}
