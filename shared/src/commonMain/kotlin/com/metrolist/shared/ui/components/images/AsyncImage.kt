package com.metrolist.shared.ui.components.images

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

/**
 * [artworkEdge]: MinMusic soft edge treatment for artwork (slight zoom + edge fade, see MmArtwork.kt).
 * On by default; pass false for avatars, logos, full-bleed backgrounds and banners. Android only.
 */
@Composable
expect fun AsyncImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.Center,
    shape: Shape? = null,
    error: Painter? = null,
    previewUrl: String? = null,
    saturation: Float = 1f,
    artworkEdge: Boolean = true,
)
