/*
 * MinMusic: soft edge treatment for every artwork / thumbnail (owner request 1.0.1).
 * YouTube thumbnails often carry a thin light border or letterbox line. The image is zoomed slightly inside its
 * clip so 1-2 px source borders are cropped away, and the outer few percent fade to transparent with an alpha
 * mask, so whatever is left blends into any background (near-black, light theme, artwork-tinted player).
 *
 * Cost: one offscreen layer per artwork plus two full-size gradient rects drawn with DstIn. Brushes are built in
 * drawWithCache and only rebuilt when the size changes; nothing recomposes. No blur.
 */

package com.metrolist.shared.ui.components.minmusic

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Artworks at or below this size count as small thumbnails and get the lighter treatment. */
private val MmArtworkSmallSide = 64.dp
private val MmArtworkMinFade = 2.dp
private val MmArtworkMaxFade = 12.dp
private const val MM_ARTWORK_FADE_FRACTION = 0.05f
private const val MM_ARTWORK_ZOOM = 1.04f
private const val MM_ARTWORK_SMALL_ZOOM = 1.03f

/** Width of the edge fade in px for an artwork whose shorter side is [minSidePx]. */
fun Density.mmArtworkFadePx(minSidePx: Float): Float {
    if (minSidePx <= 0f) return 0f
    val fade =
        if (minSidePx <= MmArtworkSmallSide.toPx()) {
            MmArtworkMinFade.toPx()
        } else {
            (minSidePx * MM_ARTWORK_FADE_FRACTION).coerceIn(MmArtworkMinFade.toPx(), MmArtworkMaxFade.toPx())
        }
    return fade.coerceAtMost(minSidePx / 2f)
}

/** Zoom applied inside the clip for an artwork whose shorter side is [minSidePx]. */
fun Density.mmArtworkZoom(minSidePx: Float): Float =
    if (minSidePx <= MmArtworkSmallSide.toPx()) MM_ARTWORK_SMALL_ZOOM else MM_ARTWORK_ZOOM

private fun mmEdgeBrushes(
    topLeft: Offset,
    size: Size,
    fade: Float,
): Pair<Brush, Brush> {
    val fx = (fade / size.width).coerceIn(0f, 0.5f)
    val fy = (fade / size.height).coerceIn(0f, 0.5f)
    val horizontal =
        Brush.horizontalGradient(
            0f to Color.Transparent,
            fx to Color.Black,
            (1f - fx) to Color.Black,
            1f to Color.Transparent,
            startX = topLeft.x,
            endX = topLeft.x + size.width,
        )
    val vertical =
        Brush.verticalGradient(
            0f to Color.Transparent,
            fy to Color.Black,
            (1f - fy) to Color.Black,
            1f to Color.Transparent,
            startY = topLeft.y,
            endY = topLeft.y + size.height,
        )
    return horizontal to vertical
}

/**
 * Multiplies the alpha of what is already drawn inside [topLeft]/[size] by the edge fade. Must run inside an
 * offscreen layer (graphicsLayer with CompositingStrategy.Offscreen or Canvas.saveLayer).
 */
fun DrawScope.drawMmArtworkEdgeMask(
    topLeft: Offset,
    size: Size,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val fade = mmArtworkFadePx(size.minDimension)
    if (fade <= 0f) return
    val (horizontal, vertical) = mmEdgeBrushes(topLeft, size, fade)
    drawRect(brush = horizontal, topLeft = topLeft, size = size, blendMode = BlendMode.DstIn)
    drawRect(brush = vertical, topLeft = topLeft, size = size, blendMode = BlendMode.DstIn)
}

/**
 * Clips to [shape], zooms the content slightly (crops thin source borders) and fades the outer edges to
 * transparent. Replaces a plain `clip(shape)` on artwork containers; the fade follows the straight edges and the
 * shape keeps the rounded corners.
 */
fun Modifier.mmArtworkEdge(shape: Shape = RectangleShape): Modifier =
    graphicsLayer {
        this.shape = shape
        clip = true
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val fade = mmArtworkFadePx(size.minDimension)
        val zoom = mmArtworkZoom(size.minDimension)
        val brushes = if (fade > 0f) mmEdgeBrushes(Offset.Zero, size, fade) else null
        onDrawWithContent {
            scale(zoom) { this@onDrawWithContent.drawContent() }
            if (brushes != null) {
                drawRect(brush = brushes.first, blendMode = BlendMode.DstIn)
                drawRect(brush = brushes.second, blendMode = BlendMode.DstIn)
            }
        }
    }
