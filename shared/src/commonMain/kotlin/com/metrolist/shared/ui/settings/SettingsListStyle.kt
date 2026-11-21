/*
 * MinMusic settings list style (design-system section 10, "Grouped list (settings)").
 */

package com.metrolist.shared.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Grouped settings rows: one surface1 card with radius 20 and hairline gaps between rows. */
object SettingsListStyle {
    val OuterCorner = 20.dp
    val InnerCorner = 4.dp
    val RowGap = 1.dp
    val RowMinHeight = 56.dp
    val RootRowMinHeight = 64.dp
}

/** Section label above a settings group: label size, weight 600, ink3, in capitals. */
@Composable
fun SettingsOverline(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.semantics { heading() },
    )
}
