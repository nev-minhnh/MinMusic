/*
 * MinMusic shared UI building blocks for browse screens (Home, Search, Library, detail pages).
 * Design source: MinMusic-notes/design/design-system.md and mockups.html.
 */

package com.metrolist.shared.ui.components.minmusic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.ui.theme.MinMotion

/** Identification colours from design-system §2.3. Only ever drawn as a small dot. */
object MmSourceColors {
    val YouTube = Color(0xFFE8453C)
    val SoundCloud = Color(0xFFF26B1D)
    val Zing = Color(0xFF9B51E0)
    val Nct = Color(0xFF2F80ED)

    fun of(source: SourceId): Color? =
        when (source) {
            SourceId.YT -> YouTube
            SourceId.SC -> SoundCloud
            SourceId.ZING -> Zing
            SourceId.NCT -> Nct
            SourceId.LOCAL -> null
        }
}

/**
 * Scales whatever it is applied to down to [MinMotion.PRESS_SCALE] while [interactionSource] is pressed.
 * Only the draw layer changes, so text and layout stay put. Apply it to artwork, not to whole rows.
 */
@Composable
fun Modifier.mmPressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) MinMotion.PRESS_SCALE else 1f,
        animationSpec = if (pressed) MinMotion.pressIn() else MinMotion.pressOut(),
        label = "mmPressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Section header: title (20/28, 600), an optional meta line (12/18, ink3) and an optional text action in the
 * accent colour. Padding follows the design: 24 above, 12 below, 16 gutter.
 */
@Composable
fun MmSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    topPadding: Dp = 24.dp,
    bottomPadding: Dp = 12.dp,
    horizontalPadding: Dp = 16.dp,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalPadding,
                    // The text button carries its own 12 dp inset, so the label still lines up with the gutter.
                    end = if (actionLabel != null) (horizontalPadding - 12.dp).coerceAtLeast(0.dp) else horizontalPadding,
                )
                .padding(top = topPadding, bottom = bottomPadding),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}
