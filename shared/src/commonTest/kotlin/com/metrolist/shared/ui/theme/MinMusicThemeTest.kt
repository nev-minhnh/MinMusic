package com.metrolist.shared.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.metrolist.shared.settings.ThemePaletteStyle
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MinMusicThemeTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (max(la, lb) / min(la, lb)).toDouble()
    }

    @Test
    fun unsetThemeColorResolvesToTheFixedBrandScheme() {
        assertEquals(DefaultThemeColor, parseThemeSeedColor(0L))
        assertSame(MinMusicDarkColorScheme, appColorScheme(parseThemeSeedColor(0L), true, ThemePaletteStyle.TONAL_SPOT))
        assertSame(MinMusicLightColorScheme, appColorScheme(DefaultThemeColor, false, ThemePaletteStyle.TONAL_SPOT))
    }

    @Test
    fun customSeedStillGeneratesAScheme() {
        val generated = appColorScheme(Color(0xFF006A6A), true, ThemePaletteStyle.TONAL_SPOT)
        assertNotEquals(MinMusicDarkColorScheme.primary, generated.primary)
    }

    @Test
    fun customAndArtworkSeedsKeepTheNeutralStage() {
        for (seed in listOf(Color(0xFF006A6A), Color(0xFF9C4146))) {
            val dark = appColorScheme(seed, true, ThemePaletteStyle.TONAL_SPOT)
            assertEquals(Color(0xFF0B0B0B), dark.surface)
            assertEquals(Color(0xFF0B0B0B), dark.background)
            assertEquals(MinMusicDarkColorScheme.surfaceContainer, dark.surfaceContainer)
            assertEquals(Color.Black, dark.pureBlack().surface)
            assertEquals(MinMusicLightColorScheme.surface, appColorScheme(seed, false, ThemePaletteStyle.TONAL_SPOT).surface)
        }
    }

    @Test
    fun darkSchemeUsesTheStageNeutrals() {
        assertEquals(Color(0xFF0B0B0B), MinMusicDarkColorScheme.surface)
        assertEquals(Color(0xFF151617), MinMusicDarkColorScheme.surfaceContainerLow)
        assertEquals(Color(0xFF1E2022), MinMusicDarkColorScheme.surfaceContainer)
        assertEquals(Color(0xFF2B2E31), MinMusicDarkColorScheme.surfaceContainerHighest)
        val black = MinMusicDarkColorScheme.pureBlack()
        assertEquals(Color.Black, black.surface)
        assertEquals(Color(0xFF0B0B0B), black.surfaceContainerLow)
        assertEquals(Color(0xFF161718), black.surfaceContainer)
    }

    @Test
    fun accentAndBodyTextPassContrastInBothThemes() {
        for (scheme in listOf(MinMusicDarkColorScheme, MinMusicLightColorScheme, MinMusicDarkColorScheme.pureBlack())) {
            assertTrue(contrast(scheme.primary, scheme.surface) >= 4.5, "primary on surface")
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= 7.0, "onSurface on surface")
            assertTrue(contrast(scheme.onSurfaceVariant, scheme.surface) >= 4.5, "onSurfaceVariant on surface")
            assertTrue(contrast(scheme.outline, scheme.surface) >= 3.0, "outline on surface")
            assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5, "onPrimary on primary")
            assertTrue(contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5, "onPrimaryContainer")
            assertTrue(contrast(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5, "onSecondaryContainer")
        }
    }

    @Test
    fun textOnTheGradientIsReadableAtBothEnds() {
        val on = MinMusicBrand.Dark.onGradient
        assertTrue(contrast(on, MinMusicPalette.BrandGreen) >= 4.5)
        assertTrue(contrast(on, MinMusicPalette.BrandBlue) >= 4.5)
    }

    @Test
    fun wallpaperSchemesKeepBrandNeutrals() {
        val generated = themeColorScheme(Color(0xFF9C4146), true, ThemePaletteStyle.TONAL_SPOT).withBrandNeutrals(true)
        assertEquals(MinMusicDarkColorScheme.surface, generated.surface)
        assertEquals(MinMusicDarkColorScheme.surfaceContainer, generated.surfaceContainer)
        assertNotEquals(MinMusicDarkColorScheme.primary, generated.primary)
    }
}
