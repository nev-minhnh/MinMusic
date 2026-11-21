/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.component

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.eq_wizard
import com.metrolist.shared.generated.resources.metroeq_device_no_profile
import org.jetbrains.compose.resources.getString

/**
 * Offers an AutoEq search, pre-filled with the device name, when a headset without a MetroEQ profile connects.
 * The action opens the full-screen equalizer route on its AutoEQ page.
 */
@Composable
internal fun EqualizerDeviceSuggestion(
    snackbarHostState: SnackbarHostState,
    onSearchAutoEq: (productName: String) -> Unit,
) {
    val suggestion = EqualizerProfiles.autoEqSuggestion
    val latestOnSearchAutoEq by rememberUpdatedState(onSearchAutoEq)
    LaunchedEffect(suggestion) {
        val device = suggestion ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = getString(Res.string.metroeq_device_no_profile, device.productName),
                actionLabel = getString(Res.string.eq_wizard),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        EqualizerProfiles.dismissAutoEqSuggestion()
        if (result == SnackbarResult.ActionPerformed) latestOnSearchAutoEq(device.productName)
    }
}
