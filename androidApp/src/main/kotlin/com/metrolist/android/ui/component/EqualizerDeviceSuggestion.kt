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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.eq_wizard
import com.metrolist.shared.generated.resources.metroeq_device_no_profile
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.equalizer.MetroEqDialog
import org.jetbrains.compose.resources.getString

/** Offers an AutoEq search, pre-filled with the device name, when a headset without a MetroEQ profile connects. */
@Composable
internal fun EqualizerDeviceSuggestion(snackbarHostState: SnackbarHostState) {
    val suggestion = EqualizerProfiles.autoEqSuggestion
    var query by remember { mutableStateOf<String?>(null) }
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
        if (result == SnackbarResult.ActionPerformed) query = device.productName
    }
    query?.let { search ->
        key(search) {
            var visible by remember { mutableStateOf(true) }
            PopupVisibility(visible = visible, onHidden = { query = null }) {
                MetroEqDialog(
                    onDismiss = { visible = false },
                    rememberProfilePicker = { onPicked -> rememberMetroEqProfilePicker(onPicked) },
                    initialAutoEqSearch = true,
                    initialAutoEqQuery = search,
                )
            }
        }
    }
}
