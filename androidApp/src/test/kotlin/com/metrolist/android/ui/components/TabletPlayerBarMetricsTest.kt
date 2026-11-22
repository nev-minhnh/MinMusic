package com.metrolist.android.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.settings.SliderStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tablet player bar must hold its controls, seek row and song zone for every slider style, thickness and font scale. */
class TabletPlayerBarMetricsTest {
    // MinMusic typography: body 14/20 (title), label 12/16 (meta, time labels).
    private fun barHeight(
        fullLayout: Boolean,
        shortHeight: Boolean = false,
        style: SliderStyle = SliderStyle.DEFAULT,
        thickness: Float = 5f,
        fontScale: Float = 1f,
        showSeekRow: Boolean = true,
    ): Dp {
        val label = (16f * fontScale).dp
        val title = (20f * fontScale).dp
        val seekRow = if (fullLayout && showSeekRow) tabletSeekRowHeight(tabletSeekTrackHeight(style, thickness), label) else 0.dp
        return tabletPlayerBarHeight(fullLayout, shortHeight, seekRow, tabletSongZoneHeight(title, label))
    }

    @Test
    fun defaultsKeepTheSpecHeights() {
        assertEquals(72.dp, barHeight(fullLayout = true))
        assertEquals(72.dp, barHeight(fullLayout = false))
        assertEquals(64.dp, barHeight(fullLayout = false, shortHeight = true))
    }

    @Test
    fun ownerSettingWavyThicknessFiveFitsInSeventyTwo() {
        // Wavy 5: wave 12.5, thumb 16 -> 16 dp track; 4 + 40 + 16 + 4 = 64 <= 72.
        assertEquals(16.dp, tabletSeekTrackHeight(SliderStyle.WAVY, 5f))
        assertEquals(72.dp, barHeight(fullLayout = true, style = SliderStyle.WAVY, thickness = 5f))
        assertEquals(72.dp, barHeight(fullLayout = true, style = SliderStyle.WAVY, thickness = 5f, fontScale = 1.3f))
    }

    @Test
    fun barGrowsInsteadOfClippingForThickTracksAndLargeFonts() {
        for (style in SliderStyle.entries) {
            for (thickness in listOf(1f, 2f, 5f, 8f, 10f, 12f)) {
                for (fontScale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                    val label = (16f * fontScale).dp
                    val title = (20f * fontScale).dp
                    val seekRow = tabletSeekRowHeight(tabletSeekTrackHeight(style, thickness), label)
                    val full = barHeight(fullLayout = true, style = style, thickness = thickness, fontScale = fontScale)
                    // Controls row + seek row + padding, and the song zone + padding, both fit.
                    assertTrue("$style $thickness $fontScale", full >= 40.dp + seekRow + 8.dp)
                    assertTrue("$style $thickness $fontScale", full >= tabletSongZoneHeight(title, label) + 8.dp)
                    assertTrue(full >= 72.dp)
                    val compact = barHeight(fullLayout = false, shortHeight = true, fontScale = fontScale)
                    assertTrue(compact >= 48.dp + 16.dp)
                    assertTrue(compact >= tabletSongZoneHeight(title, label) + 16.dp)
                }
            }
        }
    }

    @Test
    fun trackHeightsFollowTheSliderDrawing() {
        assertEquals(16.dp, tabletSeekTrackHeight(SliderStyle.DEFAULT, 10f))
        // Wavy 10: wave 25, thumb 20.
        assertEquals(25.dp, tabletSeekTrackHeight(SliderStyle.WAVY, 10f))
        assertEquals(25.dp, tabletSeekTrackHeight(SliderStyle.SQUIGGLY, 10f))
        assertEquals(16.dp, tabletSeekTrackHeight(SliderStyle.SLIM, 2f))
        // Thickness is clamped to the slider's 2..10 dp range.
        assertEquals(tabletSeekTrackHeight(SliderStyle.WAVY, 10f), tabletSeekTrackHeight(SliderStyle.WAVY, 40f))
    }

    @Test
    fun hiddenSeekRowNeedsOnlyTheControls() {
        assertEquals(72.dp, barHeight(fullLayout = true, style = SliderStyle.SQUIGGLY, thickness = 10f, showSeekRow = false))
        // Thick squiggly with the seek row: 4 + 40 + 25 + 4 = 73.
        assertEquals(73.dp, barHeight(fullLayout = true, style = SliderStyle.SQUIGGLY, thickness = 10f))
    }
}
