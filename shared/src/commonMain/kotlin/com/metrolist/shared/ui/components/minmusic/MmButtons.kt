package com.metrolist.shared.ui.components.minmusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.theme.LocalBrand

/** Primary action: 48 dp pill filled with the brand gradient, dark text and icon on top (design-system §10). */
@Composable
fun MmPrimaryPill(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val brand = LocalBrand.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .mmPressScale(interactionSource)
                .height(48.dp)
                .clip(CircleShape)
                .background(brand.gradient)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = brand.onGradient),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = brand.onGradient, modifier = Modifier.size(20.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = brand.onGradient,
            maxLines = 1,
        )
    }
}

/** Tonal 48 dp round icon button on surface2, as in the detail-page action row. */
@Composable
fun MmTonalIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (checked) colors.primaryContainer else colors.surfaceContainerHigh)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (checked) colors.onPrimaryContainer else colors.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}
