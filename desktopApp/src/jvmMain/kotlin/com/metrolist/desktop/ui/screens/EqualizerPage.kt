/*
 * MinMusic desktop: full-window MetroEQ page (design "Sân khấu tối", equalizer frames in mockups.html).
 */

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.ui.components.LocalCaptionBarInsets
import com.metrolist.desktop.ui.components.rememberMetroEqProfilePicker
import com.metrolist.desktop.ui.layouts.DesktopLayoutRules
import com.metrolist.desktop.ui.layouts.LocalDesktopLayoutMetrics
import com.metrolist.shared.ui.equalizer.MetroEqScreen

/**
 * The equalizer as its own page over the whole window, like the phone's full-screen route: the shared
 * [MetroEqScreen] in its onBack mode (graph, Bands / Effects tabs, AutoEQ, APO import). On wide windows the
 * editor keeps a readable column instead of stretching the graph edge to edge. The per-output-device card shows
 * only when an output device is known, which the desktop audio path does not report yet, so it stays hidden.
 */
@Composable
fun DesktopEqualizerPage(onBack: () -> Unit = { UiState.showEqualizer = false }) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                // Swallow clicks so nothing underneath reacts while the page is open.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ).windowInsetsPadding(LocalCaptionBarInsets.current.only(WindowInsetsSides.Top)),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Desktop layout spec section 7: Expanded with >= 1000 of content -> graph + bands | 400 effects column,
        // otherwise the phone's single column with Bands / Effects tabs.
        val metrics = LocalDesktopLayoutMetrics.current
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val twoColumns =
                DesktopLayoutRules.equalizerTwoColumns(metrics.widthClass, maxWidth - metrics.gutter * 2)
            Box(
                Modifier
                    .widthIn(max = if (twoColumns) DesktopLayoutRules.ContentMaxWidth else 960.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = if (twoColumns) metrics.gutter - 16.dp else 0.dp),
            ) {
                MetroEqScreen(
                    rememberProfilePicker = { onPicked -> rememberMetroEqProfilePicker(onPicked) },
                    onBack = onBack,
                    wideLayout = twoColumns,
                    graphHeight = metrics.eqGraphHeight,
                )
            }
        }
    }
}
