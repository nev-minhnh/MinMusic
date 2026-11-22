/*
 * Height math for the tablet player bar. Pure functions so the "never clip" rule is unit-tested: the bar is at
 * least the spec height (72, or 64 on short windows) and grows only when the user's slider style/thickness or a
 * large font scale needs more room than that.
 */

package com.metrolist.android.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.settings.SliderStyle
import kotlin.math.ceil

/** Spec 3.2: 72, or 64 on compact heights below 480 dp. */
val TabletPlayerBarHeight = 72.dp
val TabletPlayerBarCompactHeight = 64.dp

/** Full layout: icon buttons and the play button (r14) share one 40 dp control height. */
internal val TabletBarFullControlSize = 40.dp
internal val TabletBarFullVerticalPadding = 4.dp

/** Full layout: space between the controls row and the seek row (owner, 1.0.6). */
internal val TabletBarFullSeekGap = 16.dp

/** Compact layout: play 48 r16 and 44 dp icon buttons (laid out at the 48 dp touch target). */
internal val TabletBarCompactControlSize = 48.dp
internal val TabletBarCompactVerticalPadding = 8.dp

internal val TabletBarArtworkSize = 48.dp

/** M3 WavyProgressIndicatorDefaults.LinearContainerHeight, scaled by thickness / 4 dp in PlaybackProgressSlider. */
private const val WavyContainerHeightDp = 10f
private const val WavyStrokeReferenceDp = 4f

/** SquigglyProgress: amplitude 0.75 x stroke on each side of the centre line, plus the stroke itself. */
private const val SquigglyHeightPerStroke = 2.5f
private const val ThumbHeightDp = 16f
private const val MmTrackHeightDp = 16f

/**
 * Visual height of the seek track drawn by PlaybackProgressSlider for [style] and [thicknessDp]
 * (the slider clamps thickness to 2..10 dp), including the thumb.
 */
internal fun tabletSeekTrackHeight(
    style: SliderStyle,
    thicknessDp: Float,
    showThumb: Boolean = true,
): Dp {
    val t = thicknessDp.coerceIn(2f, 10f)
    val thumb = if (showThumb) ThumbHeightDp else 0f
    val height =
        when (style) {
            // MinMusic 4 dp gradient track in a 16 dp box (thumb 16 on touch-down).
            SliderStyle.DEFAULT -> MmTrackHeightDp
            SliderStyle.WAVY, SliderStyle.LINEAR_WAVY ->
                maxOf(WavyContainerHeightDp * t / WavyStrokeReferenceDp, if (showThumb) 2f * maxOf(8f, t) else 0f)
            SliderStyle.SQUIGGLY -> maxOf(SquigglyHeightPerStroke * t, thumb)
            SliderStyle.SLIM, SliderStyle.LINEAR -> maxOf(t, thumb)
        }
    return height.dp
}

/** Seek row of the full layout: time labels and the track side by side, so the taller one wins. */
internal fun tabletSeekRowHeight(
    trackHeight: Dp,
    timeLineHeight: Dp,
): Dp = maxOf(trackHeight, timeLineHeight)

/** Song zone: artwork 48 beside the title line and the meta line (badges 18 or the artist text). */
internal fun tabletSongZoneHeight(
    titleLineHeight: Dp,
    metaLineHeight: Dp,
): Dp = maxOf(TabletBarArtworkSize, titleLineHeight + maxOf(metaLineHeight, 18.dp))

/**
 * Bar height. Full layout: controls row (40) over the seek row ([seekRowHeight], 0 when hidden) with 4 dp padding.
 * Compact layout: one row of 48 dp controls with 8 dp padding. Never below the spec height; rounded up to whole dp.
 */
internal fun tabletPlayerBarHeight(
    fullLayout: Boolean,
    shortHeight: Boolean,
    seekRowHeight: Dp,
    songZoneHeight: Dp,
    seekGap: Dp = 0.dp,
): Dp {
    val base = if (shortHeight) TabletPlayerBarCompactHeight else TabletPlayerBarHeight
    val gap = if (seekRowHeight > 0.dp) seekGap else 0.dp
    val content =
        if (fullLayout) {
            maxOf(TabletBarFullControlSize + gap + seekRowHeight, songZoneHeight) + TabletBarFullVerticalPadding * 2
        } else {
            maxOf(TabletBarCompactControlSize, songZoneHeight) + TabletBarCompactVerticalPadding * 2
        }
    return maxOf(base, ceil(content.value).dp)
}
