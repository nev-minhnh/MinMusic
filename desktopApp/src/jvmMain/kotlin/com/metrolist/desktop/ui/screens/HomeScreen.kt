@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.BrowseState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.PodcastState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.components.AlbumContextMenu
import com.metrolist.desktop.ui.components.ArtistContextMenu
import com.metrolist.desktop.ui.components.EpisodeContextMenu
import com.metrolist.desktop.ui.components.PlaylistContextMenu
import com.metrolist.desktop.ui.components.PodcastContextMenu
import com.metrolist.desktop.ui.components.RetryableError
import com.metrolist.desktop.ui.components.SongContextMenu
import com.metrolist.desktop.ui.layouts.DesktopLayoutRules
import com.metrolist.desktop.ui.layouts.LocalDesktopLayoutMetrics
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.charts
import com.metrolist.shared.generated.resources.home_unavailable
import com.metrolist.shared.generated.resources.mm_greeting_afternoon
import com.metrolist.shared.generated.resources.mm_greeting_evening
import com.metrolist.shared.generated.resources.mm_greeting_morning
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pin_to_speed_dial
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.refetch
import com.metrolist.shared.generated.resources.refetch_desc
import com.metrolist.shared.generated.resources.see_all
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.unpin_from_speed_dial
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.isVideoShelf
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.DesktopHorizontalScrollControls
import com.metrolist.shared.ui.components.HomeLoadingPlaceholder
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.YTGridItem
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.minmusic.ArtworkOnPageBackground
import com.metrolist.shared.ui.components.minmusic.MmChartTab
import com.metrolist.shared.ui.components.minmusic.MmChip
import com.metrolist.shared.ui.components.minmusic.MmSourceColors
import com.metrolist.shared.ui.components.minmusic.MmTrackRow
import com.metrolist.shared.ui.components.minmusic.mmArtworkEdge
import com.metrolist.shared.ui.components.minmusic.mmChartTabLabel
import com.metrolist.shared.ui.components.minmusic.rememberMmVnCharts
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.MediaContextMenu
import com.metrolist.shared.ui.menu.contextMenuAnchor
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.screens.HomeContent
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfCache
import com.metrolist.shared.ui.screens.HomeShelfHeaderAction
import com.metrolist.shared.ui.screens.HomeShelfStyle
import com.metrolist.shared.ui.screens.homeContentText
import com.metrolist.shared.ui.screens.pageKey
import com.metrolist.shared.ui.screens.renderHomeShelves
import com.metrolist.shared.ui.screens.toHomeSection
import com.metrolist.shared.ui.theme.LocalBrand
import com.metrolist.shared.ui.theme.MdSpacing
import com.metrolist.shared.ui.theme.MinMotion
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Same key as the shared builder's quick-picks shelf (QUICK_PICKS_SHELF_KEY is internal to shared). */
private const val QUICK_PICKS_KEY = "quick_picks"

/** Quick picks show at least this many tiles, rounded up to whole rows. */
private const val QUICK_PICK_MIN_TILES = 8

@Composable
fun HomeScreen() {
    val uiState = HomeState.uiState
    val listState = rememberLazyListState()
    val contextMenu = rememberCursorContextMenuState()
    var selectedMenuItem by remember { mutableStateOf<YTItem?>(null) }
    var showSurpriseMenu by remember { mutableStateOf(false) }
    // MinMusic charts shelf: only when Zing / NCT / SoundCloud are enabled and answered; never placeholder data.
    val vnCharts by rememberMmVnCharts()
    val chartCurrentSongId = remember { derivedStateOf { PlayerState.currentTrack?.id } }
    val chartIsPlaying = remember { derivedStateOf { PlayerState.isPlaying } }
    // HomeContent hides the "current" highlight on shelves when UI enhancements are on; keep that for shared shelves.
    val shelfCurrentSongId = remember { derivedStateOf { PlayerState.currentTrack?.id.takeUnless { Settings.shared.uiEnhancements } } }
    val shelfCache = remember { HomeShelfCache() }

    fun dismissContextMenu() {
        contextMenu.dismiss()
    }

    if (uiState.showLoadingPlaceholder) {
        HomeLoadingPlaceholder(Modifier.fillMaxSize().padding(top = MdSpacing.sm))
        return
    }

    if (uiState.error != null && uiState.hasNoHomeContent()) {
        RetryableError(stringResource(Res.string.home_unavailable), HomeState::refresh)
        return
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) {
                Int.MAX_VALUE
            } else {
                listState.firstVisibleItemScrollOffset
            }
        }.collect { ArtistState.topBarScrollOffset = it }
    }

    val showItemMenu: (YTItem) -> Unit = { item ->
        if (item !is BrowseItem) {
            selectedMenuItem = item
            showSurpriseMenu = false
            contextMenu.open()
        }
    }
    val showSongMenu: (SongItem) -> Unit = { song ->
        selectedMenuItem = song
        showSurpriseMenu = false
        contextMenu.open()
    }
    val showSurprise: () -> Unit = {
        selectedMenuItem = null
        showSurpriseMenu = true
        contextMenu.open()
    }
    val text = homeContentText()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .contextMenuAnchor(contextMenu),
    ) {
        // Spec 3.4: gutters 16 / 24 / 32, Expanded content centred at <= 1600; W = content width after gutters.
        val metrics = LocalDesktopLayoutMetrics.current
        val gutter = metrics.gutter
        val pageWidth = if (metrics.isExpanded) minOf(maxWidth, DesktopLayoutRules.ContentMaxWidth) else maxWidth
        val contentWidth = (pageWidth - gutter * 2).coerceAtLeast(0.dp)
        val quickPickColumns = DesktopLayoutRules.quickPickColumns(metrics.widthClass, contentWidth)
        val chartColumns = DesktopLayoutRules.chartColumns(metrics.widthClass, contentWidth)
        val chartRows = DesktopLayoutRules.chartRows(metrics.widthClass)
        val isWide = maxWidth > 840.dp
        val cardWidth = metrics.cardWidth
        val cardGap = metrics.cardGap
        val chipChartTabs = metrics.isCompact

        val shelves = if (uiState.isContentLoading) emptyList() else shelfCache.build(uiState, text, maxWidth)
        val quickPicks = shelves.firstOrNull { it.key == QUICK_PICKS_KEY && it.style == HomeShelfStyle.SONG_GRID }
        val otherShelves = remember(shelves, quickPicks) { shelves.filter { it !== quickPicks } }
        val showCharts = vnCharts.isNotEmpty() && uiState.selectedChip == null

        HomeContent(
            uiState = uiState,
            text = text,
            onItemClick = ::openHomeItem,
            onLocalShelfClick = { shelf ->
                DesktopNavigation.navigate(Screen.HomeSection(shelf.pageKey, shelf.title, shelf.toHomeSection()))
            },
            onItemLongClick = showItemMenu,
            onShuffleSongs = { songs -> PlayerState.shuffleQueue(songs) },
            onPlaySongs = { songs -> songs.firstOrNull()?.let { PlayerState.playTrack(it, songs) } },
            onSpeedDialItemSecondaryClick = showItemMenu,
            onShowSurpriseMenu = showSurprise,
            onShowSurpriseSecondaryMenu = showSurprise,
            onLoadMore = HomeState::loadMoreSections,
            // The desktop renders every shelf itself in [header]; HomeContent keeps pagination and loading rows.
            shelves = emptyList(),
            onArtistClick = ArtistState::fetchArtistData,
            onAlbumClick = PlaylistAlbumState::fetchAlbumData,
            onPlayAlbum = { PlaylistAlbumState.playAlbum(it.id) },
            currentSongId = shelfCurrentSongId,
            isPlaying = chartIsPlaying,
            isLandscape = true,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = MdSpacing.sm, bottom = 32.dp),
            header = {
                item(key = "mm_desk_greeting", contentType = "mm_desk_greeting") {
                    DesktopPageColumn(gutter) { HomeGreeting() }
                }
                if (uiState.chips.isNotEmpty()) {
                    item(key = "chips") {
                        val scrollState = rememberScrollState()
                        DesktopHorizontalScrollControls(scrollState) {
                            ChipShelf(
                                options = uiState.chips,
                                selectedOption = uiState.selectedChip,
                                onSelectionChange = HomeState::toggleChip,
                                label = { it.title },
                                scrollState = scrollState,
                            )
                        }
                    }
                }
                if (uiState.isContentLoading) {
                    item(key = "home_loading") {
                        HomeLoadingPlaceholder(Modifier.fillMaxWidth().height(600.dp))
                    }
                } else if (!uiState.error.isNullOrBlank() && uiState.hasNoHomeContent()) {
                    item(key = "home_error") {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text.homeUnavailable)
                            TextButton(onClick = HomeState::refresh, shapes = ButtonDefaults.shapes()) {
                                Text(text.tryAgain)
                            }
                        }
                    }
                }
                if (quickPicks != null) {
                    item(key = "mm_desk_quick_picks", contentType = "mm_desk_quick_picks") {
                        DesktopPageColumn(gutter) {
                            QuickPickGrid(
                                items = quickPicks.items,
                                columns = quickPickColumns,
                                currentSongId = chartCurrentSongId.value,
                                onItemClick = ::openHomeItem,
                                onItemMenu = showItemMenu,
                            )
                        }
                    }
                }
                if (showCharts) {
                    item(key = "mm_vn_charts", contentType = "mm_vn_charts") {
                        DesktopPageColumn(gutter) {
                            DesktopChartsBox(
                                tabs = vnCharts,
                                columns = chartColumns,
                                rows = chartRows,
                                tabsAsChips = chipChartTabs,
                                currentSongId = chartCurrentSongId.value,
                                isPlaying = chartIsPlaying.value,
                                onSongClick = { songs, index -> songs.getOrNull(index)?.let { PlayerState.playTrack(it, songs) } },
                                onSongMenu = showSongMenu,
                                onSeeAll = { tab -> DesktopNavigation.navigate(Screen.SourceCharts(tab.source.key, tab.key)) },
                            )
                        }
                    }
                }
                otherShelves.forEach { shelf ->
                    if (shelf.usesDesktopCards()) {
                        item(key = shelf.key, contentType = "mm_desk_card_shelf") {
                            DesktopCardShelf(
                                shelf = shelf,
                                gutter = gutter,
                                cardWidth = cardWidth,
                                cardGap = cardGap,
                                currentSongId = chartCurrentSongId.value,
                                isPlaying = chartIsPlaying.value,
                                onItemClick = ::openHomeItem,
                                onItemMenu = showItemMenu,
                            )
                        }
                    } else {
                        renderHomeShelves(
                            shelves = listOf(shelf),
                            shuffleLabel = text.shuffle,
                            onItemClick = ::openHomeItem,
                            onItemLongClick = showItemMenu,
                            onShuffleSongs = { songs -> PlayerState.shuffleQueue(songs) },
                            onLibraryClick = null,
                            onLocalShelfClick = { local ->
                                DesktopNavigation.navigate(Screen.HomeSection(local.pageKey, local.title, local.toHomeSection()))
                            },
                            onSpeedDialItemLongClick = showItemMenu,
                            onSpeedDialItemSecondaryClick = showItemMenu,
                            onArtistClick = ArtistState::fetchArtistData,
                            onAlbumClick = PlaylistAlbumState::fetchAlbumData,
                            onPlayAlbum = { PlaylistAlbumState.playAlbum(it.id) },
                            currentSongId = shelfCurrentSongId,
                            isPlaying = chartIsPlaying,
                            onShowSurpriseMenu = showSurprise,
                            onShowSurpriseSecondaryMenu = showSurprise,
                            isLandscape = true,
                            isWide = isWide,
                            onPlaySongs = { songs -> songs.firstOrNull()?.let { PlayerState.playTrack(it, songs) } },
                        )
                    }
                }
            },
        )

        selectedMenuItem?.let { item ->
            when (item) {
                is SongItem -> {
                    SongContextMenu(
                        song = item,
                        expanded = contextMenu.expanded,
                        offset = contextMenu.offset,
                        additionalActions =
                            listOf(
                                PlayerMenuAction(
                                    MetrolistIcons.Outlined.Refresh,
                                    stringResource(Res.string.refetch),
                                    stringResource(Res.string.refetch_desc),
                                ) {
                                    HomeState.refetchSongMetadata(item)
                                    dismissContextMenu()
                                },
                            ),
                        onDismiss = ::dismissContextMenu,
                    )
                }

                is AlbumItem -> {
                    AlbumContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = ::dismissContextMenu)
                }

                is ArtistItem -> {
                    ArtistContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = ::dismissContextMenu)
                }

                is PlaylistItem -> {
                    PlaylistContextMenu(
                        item,
                        expanded = contextMenu.expanded,
                        offset = contextMenu.offset,
                        onDismiss = ::dismissContextMenu,
                    )
                }

                is PodcastItem -> {
                    PodcastContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = ::dismissContextMenu)
                }

                is EpisodeItem -> {
                    EpisodeContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = ::dismissContextMenu)
                }

                is BrowseItem -> {
                    Unit
                }
            }
        }

        if (showSurpriseMenu) {
            val isPinned = uiState.speedDialSurprisePinned
            MediaContextMenu(
                expanded = contextMenu.expanded,
                offset = contextMenu.offset,
                onDismiss = ::dismissContextMenu,
                title = stringResource(Res.string.shuffle),
                subtitle = null,
                thumbnail = null,
                fallbackIcon = MetrolistIcons.Rounded.Shuffle,
                actionGroups =
                    listOf(
                        listOf(
                            PlayerMenuAction(
                                if (isPinned) MetrolistIcons.Outlined.KeepOff else MetrolistIcons.Filled.PushPin,
                                stringResource(
                                    if (isPinned) Res.string.unpin_from_speed_dial else Res.string.pin_to_speed_dial,
                                ),
                            ) {
                                HomeState.setSpeedDialSurprisePinned(!isPinned)
                                dismissContextMenu()
                            },
                        ),
                    ),
            )
        }
    }
}

/** Centres a home section at <= 1600 dp and applies the width-class gutter. */
@Composable
private fun DesktopPageColumn(
    gutter: Dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .widthIn(max = DesktopLayoutRules.ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = gutter),
        ) {
            content()
        }
    }
}

private fun greetingFor(hour: Int): StringResource =
    when (hour) {
        in 4..10 -> Res.string.mm_greeting_morning
        in 11..17 -> Res.string.mm_greeting_afternoon
        else -> Res.string.mm_greeting_evening
    }

/** "Chào buổi tối" (28/600) with today's date (14, ink3) under it. */
@Composable
private fun HomeGreeting() {
    val greeting = remember { greetingFor(LocalTime.now().hour) }
    val date =
        remember {
            runCatching { LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) }.getOrNull()
        }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = stringResource(greeting),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (date != null) {
            Text(
                text = date.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Quick picks: [columns] x N grid of 64 dp tiles (art 64, radius 12, gap 12). */
@Composable
private fun QuickPickGrid(
    items: List<YTItem>,
    columns: Int,
    currentSongId: String?,
    onItemClick: (YTItem) -> Unit,
    onItemMenu: (YTItem) -> Unit,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val rows =
        remember(items, safeColumns) {
            val count = ((QUICK_PICK_MIN_TILES + safeColumns - 1) / safeColumns) * safeColumns
            items.take(count).chunked(safeColumns)
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    QuickPickTile(
                        item = item,
                        isCurrent = item.id == currentSongId,
                        onClick = { onItemClick(item) },
                        onMenu = { onItemMenu(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(safeColumns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Spec 5.1: surface1 -> surface2 on hover and a 36 dp gradient play squircle fades in at the right. */
@Composable
private fun QuickPickTile(
    item: YTItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrand.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val playAlpha by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = MinMotion.fast(),
        label = "quickPickPlay",
    )
    val enabled = !item.isUnavailable
    Row(
        modifier =
            modifier
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (hovered) colors.surfaceContainer else colors.surfaceContainerLow)
                .hoverable(interactionSource)
                .onSecondaryClick(onMenu)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    enabled = enabled,
                    onClick = onClick,
                ).padding(end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            url = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).mmArtworkEdge().background(colors.surfaceContainerHigh),
            artworkEdge = false,
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = if (isCurrent) colors.primary else colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .graphicsLayer { alpha = playAlpha }
                    .clip(RoundedCornerShape(12.dp))
                    .background(brand.gradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MetrolistIcons.Rounded.PlayArrow,
                contentDescription = null,
                tint = brand.onGradient,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * VN charts box (spec 3.4): header with source tabs and "See all", then a surface1 box (radius 20) with 2 columns x 5
 * ranked rows when [columns] is 2, else 1 column of [rows]. In Compact the tabs become chips under the header.
 */
@Composable
private fun DesktopChartsBox(
    tabs: List<MmChartTab>,
    columns: Int,
    rows: Int,
    tabsAsChips: Boolean,
    currentSongId: String?,
    isPlaying: Boolean,
    onSongClick: (songs: List<SongItem>, index: Int) -> Unit,
    onSongMenu: (SongItem) -> Unit,
    onSeeAll: (MmChartTab) -> Unit,
) {
    if (tabs.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    var selectedKey by rememberSaveable { mutableStateOf(tabs.first().key) }
    val selected = tabs.firstOrNull { it.key == selectedKey } ?: tabs.first()
    val perColumn = rows.coerceAtLeast(1)
    val columnCount = columns.coerceIn(1, 2)
    val songs = selected.songs
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.charts),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 1,
            )
            if (!tabsAsChips && tabs.size > 1) {
                ChartSegmentedTabs(
                    tabs = tabs,
                    selectedKey = selected.key,
                    onSelect = { selectedKey = it },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onSeeAll(selected) }) {
                Text(
                    text = stringResource(Res.string.see_all),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                    maxLines = 1,
                )
            }
        }
        if (tabsAsChips && tabs.size > 1) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
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
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceContainerLow)
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(columnCount) { column ->
                Column(Modifier.weight(1f)) {
                    val start = column * perColumn
                    val end = minOf(start + perColumn, songs.size)
                    for (index in start until end) {
                        val song = songs[index]
                        val isCurrent = song.id == currentSongId
                        MmTrackRow(
                            song = song,
                            rank = index + 1,
                            isCurrent = isCurrent,
                            isPlaying = isCurrent && isPlaying,
                            onClick = { onSongClick(songs, index) },
                            onShowMenu = { onSongMenu(song) },
                            startPadding = 8.dp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).onSecondaryClick { onSongMenu(song) },
                        )
                    }
                }
            }
        }
    }
}

/** Segmented source tabs (bg track, selected = surface2 pill, source dot). Scrolls when there are many charts. */
@Composable
private fun ChartSegmentedTabs(
    tabs: List<MmChartTab>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(colors.background)
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.key == selectedKey
            Row(
                modifier =
                    Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) colors.surfaceContainer else Color.Transparent)
                        .clickable { onSelect(tab.key) }
                        .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MmSourceColors.of(tab.source)?.let { dot ->
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                }
                Text(
                    text = mmChartTabLabel(tab),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Plain album / playlist / artist shelves get desktop cards; everything else keeps the shared renderer. */
private fun HomeShelf.usesDesktopCards(): Boolean =
    (style == HomeShelfStyle.CAROUSEL || style == HomeShelfStyle.ALBUM_CAROUSEL) &&
        !compactRows &&
        chipTitle == null &&
        label == null &&
        thumbnail == null &&
        (headerAction == HomeShelfHeaderAction.NONE || headerAction == HomeShelfHeaderAction.BROWSE) &&
        items.isNotEmpty() &&
        !items.isVideoShelf()

/**
 * Card shelf (spec 3.4): section header (20/600, 32 above / 12 below) with 36 dp tonal ‹ › buttons, then a row of
 * [cardWidth] cards with [cardGap]. The wheel scrolls the page; the buttons, Shift+wheel and touchpads scroll the row.
 */
@Composable
private fun DesktopCardShelf(
    shelf: HomeShelf,
    gutter: Dp,
    cardWidth: Dp,
    cardGap: Dp,
    currentSongId: String?,
    isPlaying: Boolean,
    onItemClick: (YTItem) -> Unit,
    onItemMenu: (YTItem) -> Unit,
) {
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val cards = remember(shelf.items) { shelf.items.distinctBy(::canonicalHomeItemId) }
    val browseId = shelf.browseId?.takeIf { shelf.headerAction == HomeShelfHeaderAction.BROWSE && it.isNotBlank() }

    fun scroll(forward: Boolean) {
        val distance = rowState.layoutInfo.viewportSize.width * 0.8f
        scope.launch { rowState.animateScrollBy(if (forward) distance else -distance) }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = DesktopLayoutRules.ContentMaxWidth).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = shelf.headerTitle ?: shelf.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (browseId != null) {
                                    Modifier.clickable { onItemClick(BrowseItem(browseId, shelf.title, params = shelf.params)) }
                                } else {
                                    Modifier
                                },
                            ),
                )
                Spacer(Modifier.weight(1f))
                ShelfArrowButton(
                    forward = false,
                    enabled = rowState.canScrollBackward,
                    onClick = { scroll(false) },
                )
                ShelfArrowButton(
                    forward = true,
                    enabled = rowState.canScrollForward,
                    onClick = { scroll(true) },
                )
            }
            ArtworkOnPageBackground {
                LazyRow(
                    state = rowState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(cardGap),
                ) {
                    items(
                        items = cards,
                        key = { item -> canonicalHomeItemId(item) },
                        contentType = { item -> item::class.simpleName },
                    ) { item ->
                        Box(Modifier.width(cardWidth)) {
                            YTGridItem(
                                item = item,
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemMenu(item) },
                                onShowMenu = onItemMenu,
                                onArtistClick = ArtistState::fetchArtistData,
                                onAlbumClick = PlaylistAlbumState::fetchAlbumData,
                                onAlbumPlay = (item as? AlbumItem)?.let { album -> { PlaylistAlbumState.playAlbum(album.id) } },
                                imageSize = cardWidth,
                                isCurrent = item.id == currentSongId,
                                isPlaying = item.id == currentSongId && isPlaying,
                                largeText = true,
                                showDurationBadge = item is EpisodeItem,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 36 dp tonal circle (surface2); 38 % opacity when the row can't scroll that way. */
@Composable
private fun ShelfArrowButton(
    forward: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector =
                if (forward) {
                    MetrolistIcons.AutoMirrored.Filled.KeyboardArrowRight
                } else {
                    MetrolistIcons.AutoMirrored.Filled.KeyboardArrowLeft
                },
            contentDescription = stringResource(if (forward) Res.string.next else Res.string.previous),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun openHomeItem(item: YTItem) {
    when (item) {
        is SongItem -> {
            PlayerState.playTrack(item)
        }

        is AlbumItem -> {
            PlaylistAlbumState.fetchAlbumData(item.id)
        }

        is ArtistItem -> {
            ArtistState.fetchArtistData(item)
        }

        is PlaylistItem -> {
            val builtin = BuiltinPlaylist.fromId(item.id)
            val collectionType = builtin?.collectionType
            when {
                builtin == BuiltinPlaylist.HISTORY -> DesktopNavigation.navigate(Screen.History)
                builtin == BuiltinPlaylist.NEW_EPISODES -> BrowseState.fetchBrowseData("VLRDPN", title = item.title)
                builtin == BuiltinPlaylist.SAVED_EPISODES -> BrowseState.fetchBrowseData("VLSE", title = item.title)
                collectionType != null -> LibraryState.navigateToAutoPlaylist(collectionType)
                else -> PlaylistAlbumState.fetchPlaylistData(item.id, item.thumbnail)
            }
        }

        is BrowseItem -> {
            BrowseState.fetchBrowseData(item.id, item.params, item.title)
        }

        is PodcastItem -> {
            PodcastState.fetch(item.id)
        }

        is EpisodeItem -> {
            PlayerState.playTrack(item.toSongItem())
        }
    }
}
