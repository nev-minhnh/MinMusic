/*
 * MinMusic: shared-element artwork for the mini <-> full player transition (design-system section 7).
 * The artwork flies from the mini player's 48 dp / r8 slot to the full player's 288 dp / r20 slot.
 * Everything happens in the draw phase from the sheet progress, so nothing recomposes per frame.
 */

package com.metrolist.shared.ui.components.minmusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Corner radius of the artwork in the mini player. */
val MmPlayerMiniArtworkRadius = 8.dp

/** Corner radius of the artwork in the full player. */
val MmPlayerFullArtworkRadius = 20.dp

/**
 * Holds both ends of the artwork flight. The player sheet provides one instance; the mini player registers
 * its artwork slot and the full player registers its settled artwork bounds and bitmap.
 */
@Stable
class MmPlayerArtworkFlight {
    /** Coordinates of the sheet that hosts both players; bounds are measured relative to it. */
    var sheetCoordinates: LayoutCoordinates? = null

    /** The sheet progress (0 collapsed, 1 expanded); only read from draw and layer blocks. */
    var progress: () -> Float = { 0f }

    private var miniCoordinates: LayoutCoordinates? = null

    private var hasMini by mutableStateOf(false)

    /** Full player artwork bounds in sheet coordinates, measured while the sheet is settled expanded. */
    private var fullBounds by mutableStateOf<Rect?>(null)

    /** The full player's current artwork. */
    var image by mutableStateOf<ImageBitmap?>(null)

    private val path = Path()

    /** True when both ends and an image are known, so the real artworks hide while the sheet moves. */
    val canFly: Boolean
        get() = hasMini && fullBounds != null && image != null

    fun attachMini(coordinates: LayoutCoordinates?) {
        miniCoordinates = coordinates
        hasMini = coordinates != null
    }

    fun updateFull(coordinates: LayoutCoordinates) {
        val sheet = sheetCoordinates?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        val bounds = sheet.localBoundingBoxOf(coordinates, clipBounds = false)
        if (!bounds.isEmpty && bounds != fullBounds) fullBounds = bounds
    }

    fun clearFull() {
        fullBounds = null
    }

    /** Draws the flying artwork for sheet [progress] in (0, 1). */
    fun draw(
        scope: DrawScope,
        progress: Float,
    ) {
        val sheet = sheetCoordinates?.takeIf { it.isAttached } ?: return
        val mini = miniCoordinates?.takeIf { it.isAttached } ?: return
        val full = fullBounds ?: return
        val bitmap = image ?: return
        val start = sheet.localBoundingBoxOf(mini, clipBounds = false)
        if (start.isEmpty || full.isEmpty) return
        val fraction = progress.coerceIn(0f, 1f)
        val rect = lerp(start, full, fraction)
        val width = rect.width.roundToInt()
        val height = rect.height.roundToInt()
        if (width <= 0 || height <= 0) return
        with(scope) {
            val startRadius = MmPlayerMiniArtworkRadius.toPx()
            val radius = startRadius + (MmPlayerFullArtworkRadius.toPx() - startRadius) * fraction
            path.reset()
            path.addRoundRect(RoundRect(rect, CornerRadius(radius)))
            // Centre-crop like ContentScale.Crop on the real artwork.
            val edge = minOf(bitmap.width, bitmap.height)
            if (edge <= 0) return
            clipPath(path) {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset((bitmap.width - edge) / 2, (bitmap.height - edge) / 2),
                    srcSize = IntSize(edge, edge),
                    dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(width, height),
                )
            }
        }
    }
}

val LocalMmPlayerArtworkFlight = staticCompositionLocalOf<MmPlayerArtworkFlight?> { null }

/** Registers this node as the mini player's artwork slot, the start of the flight. */
@Composable
fun Modifier.mmPlayerMiniArtworkSlot(): Modifier {
    val flight = LocalMmPlayerArtworkFlight.current ?: return this
    DisposableEffect(flight) { onDispose { flight.attachMini(null) } }
    return onGloballyPositioned { flight.attachMini(it) }
        .graphicsLayer {
            val progress = flight.progress()
            alpha = if (flight.canFly && progress > 0f && progress < 1f) 0f else 1f
        }
}

/**
 * Registers this node as the full player's artwork, the end of the flight, and hides it while the
 * flying copy is drawn. [progress] is the player sheet progress; it is only read in the layer block.
 */
@Composable
fun Modifier.mmPlayerFullArtworkSlot(
    image: ImageBitmap?,
    isSettledExpanded: Boolean,
    progress: () -> Float,
): Modifier {
    val flight = LocalMmPlayerArtworkFlight.current ?: return this
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    SideEffect { flight.image = image }
    DisposableEffect(flight) { onDispose { flight.clearFull() } }
    LaunchedEffect(flight, isSettledExpanded) {
        if (isSettledExpanded) {
            withFrameNanos { }
            coordinates[0]?.let(flight::updateFull)
        }
    }
    return onGloballyPositioned {
        coordinates[0] = it
        if (isSettledExpanded) flight.updateFull(it)
    }.graphicsLayer { alpha = if (flight.canFly && progress() < 1f) 0f else 1f }
}
