/*
 * MinMusic design tokens: colour ("Sân khấu tối", dark stage).
 * Source of truth: MinMusic-notes/design/design-system.md section 2.
 */

package com.metrolist.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Raw MinMusic palette. Screens should read [MaterialTheme.colorScheme] or [LocalBrand] instead. */
object MinMusicPalette {
    val BrandGreen = Color(0xFFA6E3A1)
    val BrandTeal = Color(0xFF76C6B4)
    val BrandSky = Color(0xFF6396EB)
    val BrandBlue = Color(0xFF5B7CFA)

    /** Text and icons drawn on the brand gradient, in both themes. */
    val OnGradient = Color(0xFF0B0B0B)

    val Stage = Color(0xFF0B0B0B)
    val Ink = Color(0xFFF2F4F3)

    // Dark (default)
    val DarkSurface1 = Color(0xFF151617)
    val DarkSurface2 = Color(0xFF1E2022)
    val DarkSurface3 = Color(0xFF2B2E31)
    val DarkInk2 = Color(0xFFBBBCBB) // ink @ 76% on stage
    val DarkInk3 = Color(0xFF8C8D8C) // ink @ 56% on stage
    val DarkLine = Color(0xFF272727) // ink @ 12% on stage
    val DarkAccentText = Color(0xFF9DB0FF)
    val DarkAccentFill = Color(0xFF8FA4FF)
    val DarkAccentContainer = Color(0xFF1D2547)
    val DarkOnAccentContainer = Color(0xFFDCE2FF)

    // Pure black (OLED)
    val BlackSurface1 = Color(0xFF0B0B0B)
    val BlackSurface2 = Color(0xFF161718)
    val BlackSurface3 = Color(0xFF222426)

    // Light
    val LightBackground = Color(0xFFF6F7F8)
    val LightSurface1 = Color(0xFFECEEF1)
    val LightSurface2 = Color(0xFFE2E5E9)
    val LightSurface3 = Color(0xFFD3D8DE)
    val LightInk = Color(0xFF0B0B0B)
    val LightInk2 = Color(0xFF484849) // ink @ 74% on background
    val LightInk3 = Color(0xFF727273) // ink @ 56% on background
    val LightLine = Color(0xFFDADBDC) // ink @ 12% on background
    val LightAccentText = Color(0xFF3550C8)
    val LightAccentFill = Color(0xFF4A66E0)
    val LightAccentContainer = Color(0xFFE1E7FF)
    val LightOnAccentContainer = Color(0xFF1B2A6B)

    val DarkGold = Color(0xFFE3B45E)
    val LightGold = Color(0xFF7A5A12)
    val LightGoldContainer = Color(0xFFF1E6C8)

    val GradientStops: List<Pair<Float, Color>> =
        listOf(0f to BrandGreen, 0.42f to BrandTeal, 0.8f to BrandSky, 1f to BrandBlue)
}

/** Angle of the brand gradient, CSS convention (0 = up, clockwise). */
const val BrandGradientAngleDegrees = 120f

/**
 * A linear gradient at a CSS-style [angleDegrees] that always spans the drawn bounds, so the same brush works
 * on a 2 dp progress line and on a 72 dp play button.
 */
@Immutable
class AngledLinearGradient(
    private val stops: List<Pair<Float, Color>>,
    private val angleDegrees: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val radians = angleDegrees * PI.toFloat() / 180f
        val dx = sin(radians)
        val dy = -cos(radians)
        val halfLength = (abs(size.width * dx) + abs(size.height * dy)) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        return LinearGradientShader(
            from = Offset(center.x - dx * halfLength, center.y - dy * halfLength),
            to = Offset(center.x + dx * halfLength, center.y + dy * halfLength),
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
        )
    }

    override fun equals(other: Any?): Boolean =
        other is AngledLinearGradient && other.stops == stops && other.angleDegrees == angleDegrees

    override fun hashCode(): Int = 31 * stops.hashCode() + angleDegrees.hashCode()
}

/**
 * Brand values that Material 3 has no role for. The gradient is for accents only: play button, progress,
 * primary buttons, selected states and the EQ curve (at most ~10% of a screen). It never colours text.
 */
@Immutable
data class MinMusicBrand(
    val gradient: Brush,
    val gradientColors: List<Color>,
    val onGradient: Color,
    val accentText: Color,
    val accentFill: Color,
    val gold: Color,
    val goldContainer: Color,
) {
    companion object {
        val Gradient: Brush = AngledLinearGradient(MinMusicPalette.GradientStops, BrandGradientAngleDegrees)

        val Dark =
            MinMusicBrand(
                gradient = Gradient,
                gradientColors = MinMusicPalette.GradientStops.map { it.second },
                onGradient = MinMusicPalette.OnGradient,
                accentText = MinMusicPalette.DarkAccentText,
                accentFill = MinMusicPalette.DarkAccentFill,
                gold = MinMusicPalette.DarkGold,
                goldContainer = MinMusicPalette.DarkGold.copy(alpha = 0.16f),
            )

        val Light =
            Dark.copy(
                accentText = MinMusicPalette.LightAccentText,
                accentFill = MinMusicPalette.LightAccentFill,
                gold = MinMusicPalette.LightGold,
                goldContainer = MinMusicPalette.LightGoldContainer,
            )

        fun of(isDark: Boolean): MinMusicBrand = if (isDark) Dark else Light

        /**
         * Brand values for a user-chosen accent (custom theme colour, wallpaper or song-artwork colours): the
         * gradient runs from [primary] to [tertiary] so play, progress and selected states follow the user's
         * choice instead of the fixed MinMusic green-blue. The VIP gold stays.
         */
        fun seeded(
            isDark: Boolean,
            primary: Color,
            tertiary: Color,
            onPrimary: Color,
        ): MinMusicBrand {
            val stops = listOf(0f to primary, 1f to tertiary)
            return of(isDark).copy(
                gradient = AngledLinearGradient(stops, BrandGradientAngleDegrees),
                gradientColors = listOf(primary, tertiary),
                onGradient = onPrimary,
                accentText = primary,
                accentFill = primary,
            )
        }
    }
}

val LocalBrand = staticCompositionLocalOf { MinMusicBrand.Dark }

private val DarkBase = darkColorScheme()
private val LightBase = lightColorScheme()

/** Default dark scheme: near-black stage surfaces with a periwinkle accent. */
val MinMusicDarkColorScheme: ColorScheme =
    darkColorScheme(
        primary = MinMusicPalette.DarkAccentText,
        onPrimary = MinMusicPalette.OnGradient,
        primaryContainer = MinMusicPalette.DarkAccentContainer,
        onPrimaryContainer = MinMusicPalette.DarkOnAccentContainer,
        inversePrimary = MinMusicPalette.LightAccentText,
        secondary = Color(0xFFBAC3E8),
        onSecondary = MinMusicPalette.OnGradient,
        secondaryContainer = MinMusicPalette.DarkAccentContainer,
        onSecondaryContainer = MinMusicPalette.DarkOnAccentContainer,
        tertiary = MinMusicPalette.BrandGreen,
        onTertiary = MinMusicPalette.OnGradient,
        tertiaryContainer = Color(0xFF1C3A2A),
        onTertiaryContainer = Color(0xFFCDEFC9),
        background = MinMusicPalette.Stage,
        onBackground = MinMusicPalette.Ink,
        surface = MinMusicPalette.Stage,
        onSurface = MinMusicPalette.Ink,
        surfaceVariant = MinMusicPalette.DarkSurface3,
        onSurfaceVariant = MinMusicPalette.DarkInk2,
        surfaceTint = MinMusicPalette.DarkAccentText,
        inverseSurface = MinMusicPalette.Ink,
        inverseOnSurface = MinMusicPalette.DarkSurface2,
        error = DarkBase.error,
        onError = DarkBase.onError,
        errorContainer = DarkBase.errorContainer,
        onErrorContainer = DarkBase.onErrorContainer,
        outline = MinMusicPalette.DarkInk3,
        outlineVariant = MinMusicPalette.DarkLine,
        scrim = Color.Black,
        surfaceBright = MinMusicPalette.DarkSurface3,
        surfaceContainer = MinMusicPalette.DarkSurface2,
        surfaceContainerHigh = MinMusicPalette.DarkSurface2,
        surfaceContainerHighest = MinMusicPalette.DarkSurface3,
        surfaceContainerLow = MinMusicPalette.DarkSurface1,
        surfaceContainerLowest = Color(0xFF050505),
        surfaceDim = MinMusicPalette.Stage,
    ).withBrandFixedRoles()

/** Light scheme. The gradient stays a fill with dark text; accent text drops to #3550C8. */
val MinMusicLightColorScheme: ColorScheme =
    lightColorScheme(
        primary = MinMusicPalette.LightAccentText,
        onPrimary = Color.White,
        primaryContainer = MinMusicPalette.LightAccentContainer,
        onPrimaryContainer = MinMusicPalette.LightOnAccentContainer,
        inversePrimary = MinMusicPalette.DarkAccentText,
        secondary = Color(0xFF4B5578),
        onSecondary = Color.White,
        secondaryContainer = MinMusicPalette.LightAccentContainer,
        onSecondaryContainer = MinMusicPalette.LightOnAccentContainer,
        tertiary = Color(0xFF2E7D4F),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD5F2D8),
        onTertiaryContainer = Color(0xFF0E3B22),
        background = MinMusicPalette.LightBackground,
        onBackground = MinMusicPalette.LightInk,
        surface = MinMusicPalette.LightBackground,
        onSurface = MinMusicPalette.LightInk,
        surfaceVariant = MinMusicPalette.LightSurface3,
        onSurfaceVariant = MinMusicPalette.LightInk2,
        surfaceTint = MinMusicPalette.LightAccentText,
        inverseSurface = MinMusicPalette.DarkSurface3,
        inverseOnSurface = MinMusicPalette.Ink,
        error = LightBase.error,
        onError = LightBase.onError,
        errorContainer = LightBase.errorContainer,
        onErrorContainer = LightBase.onErrorContainer,
        outline = MinMusicPalette.LightInk3,
        outlineVariant = MinMusicPalette.LightLine,
        scrim = MinMusicPalette.Stage,
        surfaceBright = Color.White,
        surfaceContainer = MinMusicPalette.LightSurface2,
        surfaceContainerHigh = MinMusicPalette.LightSurface2,
        surfaceContainerHighest = MinMusicPalette.LightSurface3,
        surfaceContainerLow = MinMusicPalette.LightSurface1,
        surfaceContainerLowest = Color.White,
        surfaceDim = MinMusicPalette.LightSurface3,
    ).withBrandFixedRoles()

private fun ColorScheme.withBrandFixedRoles(): ColorScheme =
    copy(
        primaryFixed = MinMusicPalette.DarkOnAccentContainer,
        primaryFixedDim = MinMusicPalette.DarkAccentText,
        onPrimaryFixed = Color(0xFF0B1A5C),
        onPrimaryFixedVariant = Color(0xFF2A3E9E),
        secondaryFixed = MinMusicPalette.DarkOnAccentContainer,
        secondaryFixedDim = Color(0xFFBAC3E8),
        onSecondaryFixed = Color(0xFF141B33),
        onSecondaryFixedVariant = Color(0xFF3B4566),
        tertiaryFixed = Color(0xFFCDEFC9),
        tertiaryFixedDim = MinMusicPalette.BrandGreen,
        onTertiaryFixed = Color(0xFF0E3B22),
        onTertiaryFixedVariant = Color(0xFF2E5A3C),
    )

/** The fixed MinMusic scheme for a theme. Pure black is applied on top via [pureBlack]. */
fun minMusicColorScheme(isDark: Boolean): ColorScheme = if (isDark) MinMusicDarkColorScheme else MinMusicLightColorScheme

/**
 * Keeps a generated (wallpaper) scheme's accents but swaps its neutrals for the brand ones, so Material You only
 * changes primary and containers and the app still reads as MinMusic.
 */
fun ColorScheme.withBrandNeutrals(isDark: Boolean): ColorScheme {
    val brand = minMusicColorScheme(isDark)
    return copy(
        background = brand.background,
        onBackground = brand.onBackground,
        surface = brand.surface,
        onSurface = brand.onSurface,
        surfaceVariant = brand.surfaceVariant,
        onSurfaceVariant = brand.onSurfaceVariant,
        inverseSurface = brand.inverseSurface,
        inverseOnSurface = brand.inverseOnSurface,
        outline = brand.outline,
        outlineVariant = brand.outlineVariant,
        scrim = brand.scrim,
        surfaceBright = brand.surfaceBright,
        surfaceDim = brand.surfaceDim,
        surfaceContainer = brand.surfaceContainer,
        surfaceContainerHigh = brand.surfaceContainerHigh,
        surfaceContainerHighest = brand.surfaceContainerHighest,
        surfaceContainerLow = brand.surfaceContainerLow,
        surfaceContainerLowest = brand.surfaceContainerLowest,
    )
}
