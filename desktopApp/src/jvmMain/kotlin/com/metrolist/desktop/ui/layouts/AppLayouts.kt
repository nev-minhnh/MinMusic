/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.metrolist.desktop.ui.layouts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import com.metrolist.desktop.input.triggerSearchShortcut
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryContentState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.SearchState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadState
import com.metrolist.desktop.ui.components.AddToPlaylistDialog
import com.metrolist.desktop.ui.components.CustomTitleBar
import com.metrolist.desktop.ui.components.DesktopLyricsContent
import com.metrolist.desktop.ui.components.DesktopPlayerVisibility
import com.metrolist.desktop.ui.components.DesktopQueueContent
import com.metrolist.desktop.ui.components.DeviceConnectModalSideSheet
import com.metrolist.desktop.ui.components.DeviceConnectSideSheet
import com.metrolist.desktop.ui.components.StandardBottomPlayer
import com.metrolist.desktop.ui.components.TopBarWithPlayer
import com.metrolist.desktop.ui.components.VideoMiniViewer
import com.metrolist.desktop.ui.components.deviceConnectSideSheetWidth
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.desktop_navigation_together
import com.metrolist.shared.generated.resources.downloaded
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.filter_library
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.mm_desk_nav_yours
import com.metrolist.shared.generated.resources.mm_desk_recently_played
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.generated.resources.statistics
import com.metrolist.shared.generated.resources.up_next
import com.metrolist.shared.settings.NavItem
import com.metrolist.shared.settings.NavigationId
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.defaultOpenTabRoot
import com.metrolist.shared.settings.desktopDefaultOpenTab
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.navigationId
import com.metrolist.shared.ui.navigation.screenForNavigationId
import org.jetbrains.compose.resources.stringResource
import java.awt.Cursor
import kotlin.math.roundToInt

data class LayoutState(
    val searchText: String,
    val onSearchChange: (String) -> Unit,
    val isSearchFocused: Boolean,
    val onSearchFocusChange: (Boolean) -> Unit,
    val selectedNavIndex: Int,
)

private var searchText by mutableStateOf("")
private var isSearchFocused by mutableStateOf(false)
private var selectedNavId by
    mutableStateOf(
        if (defaultOpenTabRoot(Settings.shared.desktopDefaultOpenTab()) == "LIBRARY") NavigationId.LIBRARY else NavigationId.HOME,
    )

private fun currentSelectedNavIndex(
    visibleItems: List<NavItem>,
    navigationId: String?,
): Int = visibleItems.indexOfFirst { it.id == navigationId }.coerceAtLeast(0)

private fun refreshActionFor(route: Screen): (() -> Unit)? =
    when (route) {
        Screen.Home -> {
            HomeState::refresh
        }

        Screen.Library -> {
            { LibraryContentState.fetchLibraryData(force = true) }
        }

        Screen.History,
        Screen.Statistics,
        -> {
            { LayoutUtils.refreshCurrentPage(route) }
        }

        else -> {
            null
        }
    }

internal fun reorderSidebarNavItems(
    items: List<NavItem>,
    orderedIds: List<String>,
): List<NavItem> {
    val ids = orderedIds.toSet()
    val reordered = orderedIds.mapNotNull { id -> items.firstOrNull { it.id == id } }.iterator()
    return items.map { item ->
        if (item.id in ids && reordered.hasNext()) reordered.next() else item
    }
}

/** Selects a top-level destination the same way the old navigation scaffold did. */
private fun selectNavigationId(
    id: String,
    onLibraryReselect: () -> Unit,
) {
    val destination = screenForNavigationId(id) ?: return
    val reselectLibrary =
        destination == Screen.Library && DesktopNavigation.navigator.state.topLevelRoute == Screen.Library
    selectedNavId = id
    searchText = ""
    UiState.showOverlaySidebar = false
    DesktopNavigation.navigate(destination)
    if (reselectLibrary) onLibraryReselect()
}

/**
 * The sidebar model (section 3.1): Trang chủ / Tìm kiếm / Thư viện, then "CỦA BẠN" (liked, downloaded, recently
 * played, statistics and Listen Together when enabled in the sidebar settings, Equalizer), then Cài đặt.
 */
@Composable
private fun rememberDesktopNavModel(
    route: Screen,
    onLibraryReselect: () -> Unit,
): DesktopNavModel {
    val homeLabel = stringResource(Res.string.home)
    val searchLabel = stringResource(Res.string.search)
    val libraryLabel = stringResource(Res.string.filter_library)
    val likedLabel = stringResource(Res.string.liked_songs)
    val downloadedLabel = stringResource(Res.string.downloaded)
    val historyLabel = stringResource(Res.string.mm_desk_recently_played)
    val statisticsLabel = stringResource(Res.string.statistics)
    val togetherLabel = stringResource(Res.string.desktop_navigation_together)
    val equalizerLabel = stringResource(Res.string.equalizer)
    val settingsLabel = stringResource(Res.string.settings)
    val yoursLabel = stringResource(Res.string.mm_desk_nav_yours)
    val downloadedCount by remember {
        derivedStateOf { LibraryState.downloadStates.count { it.value == DownloadState.DOWNLOADED } }
    }
    val sidebarItems = LibraryState.sidebarNavItems
    val listenTogetherEnabled = Settings.shared.listenTogetherEnabled
    val autoPlaylistType = (route as? Screen.AutoPlaylist)?.type
    val routeNavigationId = route.navigationId
    val equalizerOpen = UiState.showEqualizer
    val settingsOpen = UiState.showSettings

    fun visible(id: String): Boolean = sidebarItems.firstOrNull { it.id == id }?.visible ?: true

    val primary =
        listOf(
            DesktopNavEntry(
                key = NavigationId.HOME,
                label = homeLabel,
                icon = MetrolistIcons.Outlined.Home,
                selectedIcon = MetrolistIcons.Filled.Home,
                selected = routeNavigationId == NavigationId.HOME && autoPlaylistType == null,
                onClick = { selectNavigationId(NavigationId.HOME, onLibraryReselect) },
            ),
            DesktopNavEntry(
                key = "search",
                label = searchLabel,
                icon = MetrolistIcons.Outlined.Search,
                selectedIcon = MetrolistIcons.Filled.Search,
                selected = route == Screen.Search,
                onClick = {
                    UiState.showOverlaySidebar = false
                    triggerSearchShortcut()
                },
                shortcut = "Ctrl K",
            ),
            DesktopNavEntry(
                key = NavigationId.LIBRARY,
                label = libraryLabel,
                icon = MetrolistIcons.Outlined.LibraryMusic,
                selectedIcon = MetrolistIcons.Filled.LibraryMusic,
                selected = routeNavigationId == NavigationId.LIBRARY,
                onClick = { selectNavigationId(NavigationId.LIBRARY, onLibraryReselect) },
            ),
        )
    val yours =
        buildList {
            add(
                DesktopNavEntry(
                    key = "liked",
                    label = likedLabel,
                    icon = MetrolistIcons.Outlined.FavoriteBorder,
                    selectedIcon = MetrolistIcons.Filled.Favorite,
                    selected = autoPlaylistType == LayoutConstants.PlaylistTypes.LIKED,
                    onClick = {
                        UiState.showOverlaySidebar = false
                        LibraryState.navigateToAutoPlaylist(LayoutConstants.PlaylistTypes.LIKED)
                    },
                ),
            )
            add(
                DesktopNavEntry(
                    key = "downloaded",
                    label = downloadedLabel,
                    icon = MetrolistIcons.Outlined.Download,
                    selectedIcon = MetrolistIcons.Filled.Download,
                    selected = autoPlaylistType == LayoutConstants.PlaylistTypes.DOWNLOADED,
                    onClick = {
                        UiState.showOverlaySidebar = false
                        LibraryState.navigateToAutoPlaylist(LayoutConstants.PlaylistTypes.DOWNLOADED)
                    },
                    badge = downloadedCount.takeIf { it > 0 }?.toString(),
                ),
            )
            if (visible(NavigationId.HISTORY)) {
                add(
                    DesktopNavEntry(
                        key = NavigationId.HISTORY,
                        label = historyLabel,
                        icon = MetrolistIcons.Outlined.MusicHistory,
                        selectedIcon = MetrolistIcons.Filled.MusicHistory,
                        selected = routeNavigationId == NavigationId.HISTORY,
                        onClick = { selectNavigationId(NavigationId.HISTORY, onLibraryReselect) },
                    ),
                )
            }
            if (visible(NavigationId.STATISTICS)) {
                add(
                    DesktopNavEntry(
                        key = NavigationId.STATISTICS,
                        label = statisticsLabel,
                        icon = MetrolistIcons.Outlined.SsidChart,
                        selectedIcon = MetrolistIcons.Filled.SsidChart,
                        selected = routeNavigationId == NavigationId.STATISTICS,
                        onClick = { selectNavigationId(NavigationId.STATISTICS, onLibraryReselect) },
                    ),
                )
            }
            if (listenTogetherEnabled && visible(NavigationId.LISTEN_TOGETHER)) {
                add(
                    DesktopNavEntry(
                        key = NavigationId.LISTEN_TOGETHER,
                        label = togetherLabel,
                        icon = MetrolistIcons.Outlined.Group,
                        selectedIcon = MetrolistIcons.Filled.Groups,
                        selected = routeNavigationId == NavigationId.LISTEN_TOGETHER,
                        onClick = { selectNavigationId(NavigationId.LISTEN_TOGETHER, onLibraryReselect) },
                    ),
                )
            }
            add(
                DesktopNavEntry(
                    key = "equalizer",
                    label = equalizerLabel,
                    icon = MetrolistIcons.Outlined.Equalizer,
                    selectedIcon = MetrolistIcons.Outlined.Equalizer,
                    selected = equalizerOpen,
                    onClick = {
                        UiState.showOverlaySidebar = false
                        UiState.openEqualizer()
                    },
                    shortcut = "Ctrl E",
                ),
            )
        }
    val settings =
        DesktopNavEntry(
            key = "settings",
            label = settingsLabel,
            icon = MetrolistIcons.Outlined.Settings,
            selectedIcon = MetrolistIcons.Filled.Settings,
            selected = settingsOpen,
            onClick = {
                UiState.showOverlaySidebar = false
                UiState.showSettings = true
            },
            shortcut = "Ctrl ,",
        )
    return DesktopNavModel(
        primary = primary,
        yours = yours,
        yoursLabel = yoursLabel,
        settings = settings,
        onOpenUserAlbum = { album ->
            UiState.showOverlaySidebar = false
            PlaylistAlbumState.fetchPlaylistData(album.id, album.customThumbnailUrl ?: album.thumbnailUrl)
        },
    )
}

@Composable
fun WindowScope.ExpressLayout(
    onMaximized: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    requestBackgroundFocus: () -> Unit,
) {
    AdaptiveDesktopLayout(
        onMaximized = onMaximized,
        toggleWindowFullscreen = toggleWindowFullscreen,
        requestBackgroundFocus = requestBackgroundFocus,
        playerInTopBar = false,
    )
}

@Composable
fun WindowScope.FocusedLayout(
    onMaximized: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    requestBackgroundFocus: () -> Unit,
) {
    AdaptiveDesktopLayout(
        onMaximized = onMaximized,
        toggleWindowFullscreen = toggleWindowFullscreen,
        requestBackgroundFocus = requestBackgroundFocus,
        playerInTopBar = true,
    )
}

/**
 * The MinMusic window (spec sections 1-3): an L-shaped chrome around a quiet stage. The sidebar / rail (surface1)
 * runs from the top of the window down to the player bar; the title bar and the content share `bg`.
 * Width drives navigation (rail 64 / rail 80 + overlay sidebar / docked sidebar 256), height drives density.
 */
@Composable
private fun WindowScope.AdaptiveDesktopLayout(
    onMaximized: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    requestBackgroundFocus: () -> Unit,
    playerInTopBar: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val metrics = LocalDesktopLayoutMetrics.current
    val visibleItems by LayoutUtils.rememberVisibleNavItems()
    val navigator = LocalNavigator.current
    val libraryGridState = rememberLazyGridState()
    var activeLibraryGridState by remember { mutableStateOf(libraryGridState) }
    val route = navigator.state.currentRoute
    val routeNavigationId = route.navigationId
    val currentNavIndex = currentSelectedNavIndex(visibleItems, routeNavigationId ?: selectedNavId)
    val currentTitle by LayoutUtils.rememberCurrentTitle(route, visibleItems, currentNavIndex)

    LaunchedEffect(route) {
        routeNavigationId?.let { selectedNavId = it }
        if ((routeNavigationId != null) && (route != Screen.Search)) searchText = ""
        // The overlay sidebar closes on navigating.
        UiState.showOverlaySidebar = false
    }
    // The overlay sidebar only exists in the Medium layout.
    LaunchedEffect(metrics.widthClass) {
        if (!metrics.isMedium) UiState.showOverlaySidebar = false
    }

    val layoutState =
        LayoutState(
            searchText = searchText,
            onSearchChange = { value ->
                searchText = value
                SearchState.onQueryChange(value)
                if (value.isNotEmpty() && navigator.state.currentRoute != Screen.Search) {
                    navigator.navigate(Screen.Search)
                }
            },
            isSearchFocused = isSearchFocused,
            onSearchFocusChange = {
                isSearchFocused = it
                UiState.isSearchFocused = it
            },
            selectedNavIndex = currentNavIndex,
        )

    val navModel =
        rememberDesktopNavModel(
            route = route,
            onLibraryReselect = { activeLibraryGridState.requestScrollToItem(0) },
        )
    val sidebarDocked = metrics.isExpanded && Settings.shared.desktopSidebarDocked
    val density = LocalDensity.current
    var sidebarWidth by remember {
        mutableStateOf(
            Settings.shared.desktopSidebarWidth.dp
                .coerceIn(DesktopLayoutRules.SidebarMinWidth, DesktopLayoutRules.SidebarMaxWidth),
        )
    }
    val hasTrack = PlayerState.currentTrack != null
    val floatingPlayerBar = Settings.shared.desktopFloatingPlayerBar

    Box(Modifier.fillMaxSize().background(colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (sidebarDocked) {
                    DesktopSidebar(
                        model = navModel,
                        width = sidebarWidth,
                        titleBarHeight = metrics.titleBarHeight,
                        onCollapse = { Settings.shared.desktopSidebarDocked = false },
                    )
                    SidebarResizeHandle(
                        onDrag = { deltaPx ->
                            val deltaDp = with(density) { deltaPx.toDp() }
                            sidebarWidth =
                                (sidebarWidth + deltaDp).coerceIn(DesktopLayoutRules.SidebarMinWidth, DesktopLayoutRules.SidebarMaxWidth)
                        },
                        onDragStopped = { Settings.shared.desktopSidebarWidth = sidebarWidth.value.roundToInt() },
                        onReset = {
                            sidebarWidth = DesktopLayoutRules.SidebarWidth
                            Settings.shared.desktopSidebarWidth = DesktopLayoutRules.SidebarWidth.value.roundToInt()
                        },
                    )
                } else {
                    DesktopNavRail(
                        model = navModel,
                        compact = metrics.isCompact,
                        titleBarHeight = metrics.titleBarHeight,
                        onOpenSidebar =
                            when {
                                metrics.isExpanded -> {
                                    { Settings.shared.desktopSidebarDocked = true }
                                }

                                metrics.isMedium -> {
                                    { UiState.showOverlaySidebar = true }
                                }

                                else -> {
                                    null
                                }
                            },
                    )
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (playerInTopBar) {
                        TopBarWithPlayer(
                            colorScheme = colorScheme,
                            searchText = layoutState.searchText,
                            onSearchChange = layoutState.onSearchChange,
                            onSearchFocusChange = layoutState.onSearchFocusChange,
                            onMaximized = onMaximized,
                            toggleWindowFullscreen = toggleWindowFullscreen,
                            showSearchInTopBar = true,
                            onRefresh = refreshActionFor(route),
                        )
                    } else {
                        CustomTitleBar(
                            requestBackgroundFocus = requestBackgroundFocus,
                            title = currentTitle,
                            colorScheme = colorScheme,
                            searchText = layoutState.searchText,
                            onSearchChange = layoutState.onSearchChange,
                            onSearchFocusChange = layoutState.onSearchFocusChange,
                            onMaximize = onMaximized,
                            selectedNavIndex = layoutState.selectedNavIndex,
                            hideSearch = metrics.isCompact,
                            onRefresh = refreshActionFor(route),
                        )
                    }

                    MainLayoutContent(
                        layoutState = layoutState,
                        colorScheme = colorScheme,
                        route = route,
                        libraryGridState = libraryGridState,
                        onLibraryScrollStateChange = { activeLibraryGridState = it },
                    )

                    if (!playerInTopBar && hasTrack && floatingPlayerBar) {
                        DesktopPlayerVisibility(visible = !PlayerState.isExpanded) {
                            StandardBottomPlayer(colorScheme, toggleWindowFullscreen, floating = true)
                        }
                    }

                    AddToPlaylistDialog()
                }
            }

            if (!playerInTopBar && hasTrack && !floatingPlayerBar) {
                DesktopPlayerVisibility(visible = !PlayerState.isExpanded) {
                    StandardBottomPlayer(colorScheme, toggleWindowFullscreen, floating = false)
                }
            }
        }

        OverlaySidebar(
            visible = UiState.showOverlaySidebar && metrics.isMedium,
            model = navModel,
            titleBarHeight = metrics.titleBarHeight,
        )
    }
}

/** Medium layout: the full 256 sidebar over the content with a 32 % scrim; Esc, a click outside or navigating closes it. */
@Composable
private fun OverlaySidebar(
    visible: Boolean,
    model: DesktopNavModel,
    titleBarHeight: Dp,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(120)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { UiState.showOverlaySidebar = false },
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(200)) { -it } + fadeIn(animationSpec = tween(120)),
        exit = slideOutHorizontally(animationSpec = tween(160)) { -it } + fadeOut(animationSpec = tween(120)),
    ) {
        DesktopSidebar(
            model = model,
            width = DesktopLayoutRules.SidebarWidth,
            titleBarHeight = titleBarHeight,
            onCollapse = { UiState.showOverlaySidebar = false },
            modifier =
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        )
    }
}

/** Invisible 6 dp drag zone on the sidebar's edge (216-320); double-click resets to 256. */
@Composable
private fun SidebarResizeHandle(
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    onReset: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val resizeCursor = remember { PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)) }
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .pointerHoverIcon(resizeCursor)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onDoubleClick = onReset,
                    onClick = {},
                ).draggable(
                    orientation = Orientation.Horizontal,
                    interactionSource = interactionSource,
                    state = rememberDraggableState { delta -> onDrag(delta) },
                    onDragStopped = { onDragStopped() },
                ),
    )
}

@Composable
private fun PaneResizeHandle(
    onDrag: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val resizeCursor = remember { PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)) }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .width(12.dp)
                .pointerHoverIcon(resizeCursor)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onDoubleClick = onReset,
                    onClick = {},
                ).draggable(
                    orientation = Orientation.Horizontal,
                    interactionSource = interactionSource,
                    state = rememberDraggableState { delta -> onDrag(delta) },
                ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDragHandle(
            interactionSource = interactionSource,
        )
    }
}

/**
 * Content plus the "Tiếp theo / Lời bài hát" supporting pane (section 3.5): one pane with tabs, 336 wide (drag
 * 280-480). It docks to the right when the content keeps >= 760 beside it, otherwise it overlays from the right
 * edge without a scrim (Esc or a click outside closes it); in Compact it covers the whole content area.
 */
@Composable
private fun ColumnScope.MainLayoutContent(
    layoutState: LayoutState,
    colorScheme: ColorScheme,
    route: Screen,
    libraryGridState: LazyGridState,
    onLibraryScrollStateChange: (LazyGridState) -> Unit,
) {
    val metrics = LocalDesktopLayoutMetrics.current
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val deviceConnectWidth = deviceConnectSideSheetWidth(maxWidth)
        val deviceConnectOverlaysContent = maxWidth - deviceConnectWidth < LayoutConstants.MinimumPrimaryPaneWidth
        val density = LocalDensity.current
        var paneWidth by remember { mutableStateOf(DesktopLayoutRules.SupportingPaneWidth) }
        val maxPaneWidth =
            (maxWidth - LayoutConstants.MinimumSupportingPaneWidth)
                .coerceIn(DesktopLayoutRules.SupportingPaneMinWidth, DesktopLayoutRules.SupportingPaneMaxWidth)
        val effectivePaneWidth = paneWidth.coerceIn(DesktopLayoutRules.SupportingPaneMinWidth, maxPaneWidth)
        val paneDocked = DesktopLayoutRules.dockSupportingPane(metrics.widthClass, maxWidth, effectivePaneWidth)

        val hasTrack = PlayerState.currentTrack != null
        val isPaneOpen = hasTrack && (UiState.showPersistentPlayerDrawer || UiState.showLyricsDrawer)

        SideEffect {
            // One pane with tabs: queue and lyrics never show side by side.
            if (!PlayerState.isExpanded) {
                UiState.playerPanels.canShowBoth = false
            }
            UiState.isDeviceConnectPanelModal = deviceConnectOverlaysContent
            UiState.supportingPaneOverlays = !paneDocked
        }

        Box(Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                StandardContentSurface(
                    route = route,
                    layoutState = layoutState,
                    colorScheme = colorScheme,
                    libraryGridState = libraryGridState,
                    onLibraryScrollStateChange = onLibraryScrollStateChange,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )

                if (paneDocked) {
                    AnimatedVisibility(
                        visible = isPaneOpen,
                        enter =
                            expandHorizontally(
                                expandFrom = Alignment.End,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                            ) + fadeIn(animationSpec = tween(200)),
                        exit =
                            shrinkHorizontally(
                                shrinkTowards = Alignment.End,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                            ) + fadeOut(animationSpec = tween(150)),
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            PaneResizeHandle(
                                onDrag = { deltaPx ->
                                    val deltaDp = with(density) { -deltaPx.toDp() }
                                    paneWidth = (paneWidth + deltaDp).coerceIn(DesktopLayoutRules.SupportingPaneMinWidth, maxPaneWidth)
                                },
                                onReset = { paneWidth = DesktopLayoutRules.SupportingPaneWidth },
                            )
                            SupportingPane(
                                colorScheme = colorScheme,
                                modifier = Modifier.width(effectivePaneWidth).fillMaxHeight().padding(top = 0.dp, end = 8.dp, bottom = 8.dp),
                            )
                        }
                    }
                }

                if (!deviceConnectOverlaysContent) {
                    DeviceConnectSideSheet(panelWidth = deviceConnectWidth)
                }
            }

            if (!paneDocked) {
                // Click outside closes the overlaying pane; no scrim.
                if (isPaneOpen && !metrics.isCompact) {
                    Box(
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            UiState.showPersistentPlayerDrawer = false
                            UiState.showLyricsDrawer = false
                        },
                    )
                }
                // Fully qualified: inside this Box the outer ColumnScope overload would otherwise be picked.
                androidx.compose.animation.AnimatedVisibility(
                    visible = isPaneOpen,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    enter = slideInHorizontally(animationSpec = tween(220)) { it } + fadeIn(animationSpec = tween(150)),
                    exit = slideOutHorizontally(animationSpec = tween(180)) { it } + fadeOut(animationSpec = tween(120)),
                ) {
                    SupportingPane(
                        colorScheme = colorScheme,
                        modifier =
                            Modifier
                                .then(if (metrics.isCompact) Modifier.fillMaxWidth() else Modifier.width(effectivePaneWidth))
                                .fillMaxHeight()
                                .padding(end = 8.dp, bottom = 8.dp, start = if (metrics.isCompact) 8.dp else 0.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {},
                        elevated = true,
                    )
                }
            }

            if (deviceConnectOverlaysContent) {
                DeviceConnectModalSideSheet(panelWidth = deviceConnectWidth)
            }
        }
    }
}

/** The tabbed "Tiếp theo / Lời bài hát" pane: a surface1 card, radius 20, inset 8 from the window edge. */
@Composable
private fun SupportingPane(
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    val showingLyrics = UiState.showLyricsDrawer
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surfaceContainerLow,
        shadowElevation = if (elevated) 8.dp else 0.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaneTab(
                    label = stringResource(Res.string.up_next),
                    selected = !showingLyrics,
                    onClick = { UiState.showPersistentPlayerDrawer = true },
                )
                PaneTab(
                    label = stringResource(Res.string.lyrics),
                    selected = showingLyrics,
                    onClick = { UiState.showLyricsDrawer = true },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (showingLyrics) {
                    DesktopLyricsContent(
                        onClose = { UiState.showLyricsDrawer = false },
                        colorScheme = colorScheme,
                        modifier = Modifier.fillMaxSize(),
                        isVisible = !PlayerState.isExpanded && !UiState.videoFullscreenObscuresPlayer,
                    )
                } else {
                    DesktopQueueContent(
                        onDismiss = { UiState.showPersistentPlayerDrawer = false },
                        colorScheme = colorScheme,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Segmented tab: 32 high chip, selected = ink fill (not gradient), hover = ink 6 %. */
@Composable
private fun PaneTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) colorScheme.onSurface else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colorScheme.surface else colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The content stage: flat `bg` shared with the title bar (no inset card). */
@Composable
private fun StandardContentSurface(
    route: Screen,
    layoutState: LayoutState,
    colorScheme: ColorScheme,
    libraryGridState: LazyGridState,
    onLibraryScrollStateChange: (LazyGridState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(colorScheme.background)) {
        AppContentHost(
            route = route,
            onSearchChange = layoutState.onSearchChange,
            colorScheme = colorScheme,
            libraryGridState = libraryGridState,
            onLibraryScrollStateChange = onLibraryScrollStateChange,
            modifier = Modifier.fillMaxSize(),
        )
        VideoMiniViewer(
            colorScheme = colorScheme,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
        )
    }
}
