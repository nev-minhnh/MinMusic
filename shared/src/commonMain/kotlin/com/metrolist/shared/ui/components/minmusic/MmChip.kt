package com.metrolist.shared.ui.components.minmusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.theme.MinMotion

private val ChipHeight = 32.dp

/**
 * Design-system chip: 32 dp, full radius, 1 dp outline. Selected = ink fill with background-coloured text
 * (deliberately not the brand gradient). [dotColor] adds the 8 dp source dot; [leadingText] adds a muted
 * prefix such as a trending rank.
 */
@Composable
fun MmChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    leadingText: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) colors.onSurface else Color.Transparent,
        animationSpec = MinMotion.fast(),
        label = "mmChipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.surface else colors.onSurfaceVariant,
        animationSpec = MinMotion.fast(),
        label = "mmChipContent",
    )
    Row(
        modifier =
            modifier
                .height(ChipHeight)
                .clip(CircleShape)
                .background(container)
                .then(if (selected) Modifier else Modifier.border(1.dp, colors.outlineVariant, CircleShape))
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
        }
        if (leadingText != null) {
            Text(
                text = leadingText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) content else colors.outline,
                maxLines = 1,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1,
            softWrap = false,
        )
    }
}
