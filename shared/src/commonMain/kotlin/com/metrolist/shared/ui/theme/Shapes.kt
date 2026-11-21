/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.settings.Settings

/** MinMusic corner radii (design-system section 5). */
object MinRadius {
    val xs = 4.dp // badges
    val sm = 8.dp // list artwork 40/48, mini-player art
    val md = 12.dp // tiles, text fields, steppers
    val lg = 16.dp // mini player, album header art, banners
    val xl = 20.dp // cards, grouped lists, player artwork, EQ card
    val sheet = 28.dp // bottom sheets (top corners)
    val play = 24.dp // player play button (squircle on 72 dp)
}

val MetrolistShapes =
    Shapes(
        extraSmall = RoundedCornerShape(MinRadius.xs),
        small = RoundedCornerShape(MinRadius.sm),
        medium = RoundedCornerShape(MinRadius.md),
        large = RoundedCornerShape(MinRadius.lg),
        extraLarge = RoundedCornerShape(MinRadius.sheet),
    )

object ComponentShapes {
    val BadgeShape = RoundedCornerShape(MinRadius.xs)
    val CardShape = RoundedCornerShape(MinRadius.xl)
    val BottomSheetShape = RoundedCornerShape(topStart = MinRadius.sheet, topEnd = MinRadius.sheet)
    val SmallThumbnailShape = RoundedCornerShape(MinRadius.sm)
    val ThumbnailShape = RoundedCornerShape(MinRadius.md)
    val AlbumArtShape = RoundedCornerShape(MinRadius.lg)
    val PlayerArtworkShape = RoundedCornerShape(MinRadius.xl)
    val PlayButtonShape = RoundedCornerShape(MinRadius.play)
    val MenuExteriorShape = RoundedCornerShape(MinRadius.sheet)
    val MenuGroupShape = RoundedCornerShape(MinRadius.xl)
    val MenuItemShape = RoundedCornerShape(MinRadius.sm)
    val SearchExteriorCornerRadius = 24.dp
    val SearchItemCornerRadius = 6.dp
}

fun <T> uiEnhanced(
    legacy: T,
    enhanced: T,
): T = if (Settings.shared.uiEnhancements) enhanced else legacy

fun gridCellShape(): RoundedCornerShape = RoundedCornerShape(MinRadius.md)

fun gridCellInset(): Dp = 4.dp
