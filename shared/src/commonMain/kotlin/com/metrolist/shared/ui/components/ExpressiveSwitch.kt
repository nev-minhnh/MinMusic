/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.LocalBrand
import com.metrolist.shared.ui.theme.MinMusicPalette

// Size of the Material 3 switch track; the brand gradient is drawn behind it, centred in the switch bounds.
private val TrackWidth = 52.dp
private val TrackHeight = 32.dp

@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberMetrolistHaptics()
    val brand = LocalBrand.current
    // MinMusic switch: the on-track is the brand gradient and the thumb is #0B0B0B (design-system section 10).
    val gradientTrack =
        if (checked) {
            Modifier.drawBehind {
                val width = TrackWidth.toPx()
                val height = TrackHeight.toPx()
                drawRoundRect(
                    brush = brand.gradient,
                    topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(height / 2f),
                    alpha = if (enabled) 1f else 0.38f,
                )
            }
        } else {
            Modifier
        }
    Switch(
        checked = checked,
        onCheckedChange =
            onCheckedChange?.let { onChange ->
                { value ->
                    haptics.toggle(value)
                    onChange(value)
                }
            },
        modifier = modifier.then(gradientTrack),
        enabled = enabled,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = brand.onGradient,
                checkedTrackColor = Color.Transparent,
                checkedBorderColor = Color.Transparent,
                checkedIconColor = MinMusicPalette.Ink,
                disabledCheckedThumbColor = brand.onGradient.copy(alpha = 0.6f),
                disabledCheckedTrackColor = Color.Transparent,
                disabledCheckedBorderColor = Color.Transparent,
                disabledCheckedIconColor = MinMusicPalette.Ink.copy(alpha = 0.6f),
            ),
        thumbContent = {
            if (checked) {
                Icon(
                    imageVector = MetrolistIcons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            } else {
                Icon(
                    imageVector = MetrolistIcons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        },
    )
}
