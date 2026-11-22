/*
 * MinMusic desktop layout metrics (design/desktop/desktop-layout-spec.md, sections 2-3).
 *
 * Width drives navigation, height drives density. The classes change rarely, so composables read
 * [LocalDesktopLayoutMetrics] (provided once at the app root) and only recompose when a class flips, never on every
 * resize frame. Code that needs the exact size measures its own box (BoxWithConstraints) and feeds it to the pure
 * helpers in [DesktopLayoutRules].
 */

package com.metrolist.desktop.ui.layouts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.floor
import kotlin.math.min

/** Width class: Compact < 840 (rail 64), Medium 840-1199 (rail 80 + overlay sidebar), Expanded >= 1200 (sidebar 256). */
enum class DesktopWidthClass { COMPACT, MEDIUM, EXPANDED }

/** Height class: Short < 760, Regular 760-999, Tall >= 1000. */
enum class DesktopHeightClass { SHORT, REGULAR, TALL }

@Immutable
data class DesktopLayoutMetrics(
    val widthClass: DesktopWidthClass,
    val heightClass: DesktopHeightClass,
) {
    val isCompact: Boolean get() = widthClass == DesktopWidthClass.COMPACT
    val isMedium: Boolean get() = widthClass == DesktopWidthClass.MEDIUM
    val isExpanded: Boolean get() = widthClass == DesktopWidthClass.EXPANDED
    val isShort: Boolean get() = heightClass == DesktopHeightClass.SHORT
    val isTall: Boolean get() = heightClass == DesktopHeightClass.TALL

    /** Content gutter `g`: 16 / 24 / 32. */
    val gutter: Dp
        get() =
            when (widthClass) {
                DesktopWidthClass.COMPACT -> 16.dp
                DesktopWidthClass.MEDIUM -> 24.dp
                DesktopWidthClass.EXPANDED -> 32.dp
            }

    /** Title bar: 40 when Short, else 48. */
    val titleBarHeight: Dp get() = if (isShort) 40.dp else 48.dp

    /** Player bar: Expanded 88 / Medium 80 / Compact 64; Short caps Expanded and Medium at 72. */
    val playerBarHeight: Dp
        get() =
            when {
                widthClass == DesktopWidthClass.COMPACT -> 64.dp
                isShort -> 72.dp
                widthClass == DesktopWidthClass.MEDIUM -> 80.dp
                else -> 88.dp
            }

    /** Player bar artwork: 56 (48 when Short) / 48 / 40. */
    val playerBarArtSize: Dp
        get() =
            when {
                widthClass == DesktopWidthClass.COMPACT -> 40.dp
                widthClass == DesktopWidthClass.MEDIUM || isShort -> 48.dp
                else -> 56.dp
            }

    /** Player bar play button: 40 (36 when Short). */
    val playerBarPlaySize: Dp get() = if (isShort && widthClass != DesktopWidthClass.COMPACT) 36.dp else 40.dp

    /** Navigation width when the sidebar is not docked: rail 64 (Compact) or 80. */
    val railWidth: Dp get() = if (isCompact) DesktopLayoutRules.RailCompactWidth else DesktopLayoutRules.RailMediumWidth

    /** Title bar search field: hidden (Compact), 360 (Medium), 480 (Expanded). */
    val searchFieldWidth: Dp
        get() =
            when (widthClass) {
                DesktopWidthClass.COMPACT -> 0.dp
                DesktopWidthClass.MEDIUM -> 360.dp
                DesktopWidthClass.EXPANDED -> 480.dp
            }

    /** Search field height: 40, 32 when Short. */
    val searchFieldHeight: Dp get() = if (isShort) 32.dp else 40.dp

    /** Album / playlist header artwork: 168 when Short, else 232. */
    val albumHeaderArtSize: Dp get() = if (isShort) 168.dp else 232.dp

    /** Full-player artwork cap: 320 / 440 / 520. */
    val fullPlayerArtCap: Dp
        get() =
            when (heightClass) {
                DesktopHeightClass.SHORT -> 320.dp
                DesktopHeightClass.REGULAR -> 440.dp
                DesktopHeightClass.TALL -> 520.dp
            }

    /** Equalizer graph height: 220 / 280 / 360. */
    val eqGraphHeight: Dp
        get() =
            when (heightClass) {
                DesktopHeightClass.SHORT -> 220.dp
                DesktopHeightClass.REGULAR -> 280.dp
                DesktopHeightClass.TALL -> 360.dp
            }

    /** Shelf card width and gap: 144/16, 160/24, 176/24. */
    val cardWidth: Dp
        get() =
            when (widthClass) {
                DesktopWidthClass.COMPACT -> 144.dp
                DesktopWidthClass.MEDIUM -> 160.dp
                DesktopWidthClass.EXPANDED -> 176.dp
            }
    val cardGap: Dp get() = if (isCompact) 16.dp else 24.dp

    companion object {
        val Default = DesktopLayoutMetrics(DesktopWidthClass.EXPANDED, DesktopHeightClass.REGULAR)
    }
}

/** Pure layout rules. `contentWidth` is the content width after gutters (and after a docked pane). */
object DesktopLayoutRules {
    val CompactMaxWidth = 840.dp
    val ExpandedMinWidth = 1200.dp
    val ShortMaxHeight = 760.dp
    val TallMinHeight = 1000.dp

    /** A class only changes after the width crossed the boundary by this much (or after the resize ends). */
    val Hysteresis = 16.dp

    val RailCompactWidth = 64.dp
    val RailMediumWidth = 80.dp
    val SidebarWidth = 256.dp
    val SidebarMinWidth = 216.dp
    val SidebarMaxWidth = 320.dp

    val SupportingPaneWidth = 336.dp
    val SupportingPaneMinWidth = 280.dp
    val SupportingPaneMaxWidth = 480.dp

    /** The content must keep at least this much next to a docked "Tiếp theo / Lời bài hát" pane. */
    val DockedPaneMinContentWidth = 760.dp

    /** Expanded content is centred beyond this width. */
    val ContentMaxWidth = 1600.dp

    const val MinWindowWidth = 640
    const val MinWindowHeight = 480

    fun widthClassFor(width: Dp): DesktopWidthClass =
        when {
            width < CompactMaxWidth -> DesktopWidthClass.COMPACT
            width < ExpandedMinWidth -> DesktopWidthClass.MEDIUM
            else -> DesktopWidthClass.EXPANDED
        }

    fun heightClassFor(height: Dp): DesktopHeightClass =
        when {
            height < ShortMaxHeight -> DesktopHeightClass.SHORT
            height < TallMinHeight -> DesktopHeightClass.REGULAR
            else -> DesktopHeightClass.TALL
        }

    /** Width class with 16 dp hysteresis around the previous class, so dragging across 1200 doesn't flicker. */
    fun widthClassWithHysteresis(
        previous: DesktopWidthClass?,
        width: Dp,
    ): DesktopWidthClass {
        val raw = widthClassFor(width)
        if (previous == null || raw == previous) return raw
        return if (raw > previous) {
            widthClassFor(width - Hysteresis).coerceAtLeast(previous)
        } else {
            widthClassFor(width + Hysteresis).coerceAtMost(previous)
        }
    }

    /** Quick-pick columns: 2 / 3 / clamp(floor(W / 280), 3, 6). */
    fun quickPickColumns(
        widthClass: DesktopWidthClass,
        contentWidth: Dp,
    ): Int =
        when (widthClass) {
            DesktopWidthClass.COMPACT -> 2
            DesktopWidthClass.MEDIUM -> 3
            DesktopWidthClass.EXPANDED -> floor(contentWidth.value / 280f).toInt().coerceIn(3, 6)
        }

    /** Library grid columns: floor((W + 24) / (184 + 24)), at least 2. */
    fun libraryGridColumns(contentWidth: Dp): Int = floor((contentWidth.value + 24f) / 208f).toInt().coerceAtLeast(2)

    /** Charts box: 2 columns x 5 when Expanded and W >= 960, else 1 column. */
    fun chartColumns(
        widthClass: DesktopWidthClass,
        contentWidth: Dp,
    ): Int = if (widthClass == DesktopWidthClass.EXPANDED && contentWidth >= 960.dp) 2 else 1

    /** Rows per chart column: 3 in Compact, else 5. */
    fun chartRows(widthClass: DesktopWidthClass): Int = if (widthClass == DesktopWidthClass.COMPACT) 3 else 5

    /** Search: top result 5fr / songs 7fr side by side when Expanded and W >= 1024. */
    fun searchTwoColumns(
        widthClass: DesktopWidthClass,
        contentWidth: Dp,
    ): Boolean = widthClass == DesktopWidthClass.EXPANDED && contentWidth >= 1024.dp

    /** Song tables hide "Đã thêm" below 880 and "Album" below 720. */
    fun showAddedColumn(contentWidth: Dp): Boolean = contentWidth >= 880.dp

    fun showAlbumColumn(contentWidth: Dp): Boolean = contentWidth >= 720.dp

    /** Settings: categories 248 + detail (<= 760) side by side only when Expanded. */
    fun settingsTwoColumns(widthClass: DesktopWidthClass): Boolean = widthClass == DesktopWidthClass.EXPANDED

    /** Equalizer: graph column + 400 effects column when Expanded and the content is >= 1000. */
    fun equalizerTwoColumns(
        widthClass: DesktopWidthClass,
        contentWidth: Dp,
    ): Boolean = widthClass == DesktopWidthClass.EXPANDED && contentWidth >= 1000.dp

    /** The supporting pane docks when the content keeps >= 760 beside it, otherwise it overlays. */
    fun dockSupportingPane(
        widthClass: DesktopWidthClass,
        areaWidth: Dp,
        paneWidth: Dp,
    ): Boolean = widthClass != DesktopWidthClass.COMPACT && areaWidth - paneWidth >= DockedPaneMinContentWidth

    // Full player (section 3.7).

    /** Two panes when the window is >= 960 wide and >= 600 tall. */
    fun fullPlayerTwoPanes(
        width: Dp,
        height: Dp,
    ): Boolean = width >= 960.dp && height >= 600.dp

    /** Left pane = clamp(400, 40 % of width, 640). */
    fun fullPlayerLeftPaneWidth(width: Dp): Dp = (width * 0.4f).coerceIn(400.dp, 640.dp)

    /** Gap between the panes: 64 (48 under 1200). */
    fun fullPlayerPaneGap(width: Dp): Dp = if (width < 1200.dp) 48.dp else 64.dp

    /** Side padding: 64 (32 under 1200). */
    fun fullPlayerSidePadding(width: Dp): Dp = if (width < 1200.dp) 32.dp else 64.dp

    /** Artwork = min(leftPane - 120, H - 400), clamped by the height-class cap. Never below 160. */
    fun fullPlayerArtworkSize(
        leftPaneWidth: Dp,
        windowHeight: Dp,
        cap: Dp,
    ): Dp = Dp(min((leftPaneWidth - 120.dp).value, (windowHeight - 400.dp).value)).coerceAtMost(cap).coerceAtLeast(160.dp)
}

val LocalDesktopLayoutMetrics = compositionLocalOf { DesktopLayoutMetrics.Default }

/** Mirror of the current metrics for code outside the composition tree that provides them (window frame). */
object DesktopLayoutMetricsState {
    var current by mutableStateOf(DesktopLayoutMetrics.Default)
        internal set
}

private class DesktopLayoutMetricsHolder {
    var lastWidthClass: DesktopWidthClass? = null
    var settledWidth by mutableFloatStateOf(-1f)
}

/**
 * Derives the classes from the window size with hysteresis; the result only changes when a class flips. After the
 * resize settles (250 ms) the plain breakpoint wins, so a window left at 1205 dp is Expanded.
 */
@Composable
fun ProvideDesktopLayoutMetrics(content: @Composable () -> Unit) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val holder = remember { DesktopLayoutMetricsHolder() }
    val metricsState =
        remember(holder, windowInfo, density) {
            derivedStateOf(structuralEqualityPolicy()) {
                val size = windowInfo.containerSize
                val widthDp = size.width / density.density
                val heightDp = size.height / density.density
                val widthClass =
                    if (holder.settledWidth == widthDp) {
                        DesktopLayoutRules.widthClassFor(Dp(widthDp))
                    } else {
                        DesktopLayoutRules.widthClassWithHysteresis(holder.lastWidthClass, Dp(widthDp))
                    }
                holder.lastWidthClass = widthClass
                DesktopLayoutMetrics(widthClass, DesktopLayoutRules.heightClassFor(Dp(heightDp)))
            }
        }
    LaunchedEffect(holder, windowInfo, density) {
        snapshotFlow { windowInfo.containerSize.width / density.density }
            .collectLatest { width ->
                holder.settledWidth = -1f
                delay(250)
                holder.settledWidth = width
            }
    }
    val metrics by metricsState
    SideEffect { DesktopLayoutMetricsState.current = metrics }
    CompositionLocalProvider(LocalDesktopLayoutMetrics provides metrics, content = content)
}
