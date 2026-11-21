/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.launchSystemEqualizer
import com.metrolist.android.ui.component.rememberMetroEqProfilePicker
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.system_equalizer_unavailable
import com.metrolist.shared.ui.equalizer.MetroEqScreen
import com.metrolist.shared.ui.settings.LocalSettingsPageBottomPadding
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen MetroEQ route. The player menu, Settings and the new-headset suggestion all open this; the
 * system equalizer lives in its overflow menu.
 */
@Composable
fun EqualizerScreen(
    onNavigateBack: () -> Unit,
    autoEqQuery: String? = null,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val systemEqualizerUnavailable = stringResource(Res.string.system_equalizer_unavailable)
    val systemEqualizerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    // Keeps the last controls clear of the mini player.
    CompositionLocalProvider(LocalSettingsPageBottomPadding provides LocalMiniPlayerBottomInset.current) {
        MetroEqScreen(
            rememberProfilePicker = { onPicked -> rememberMetroEqProfilePicker(onPicked) },
            initialAutoEqSearch = autoEqQuery != null,
            initialAutoEqQuery = autoEqQuery.orEmpty(),
            onBack = onNavigateBack,
            onOpenSystemEqualizer = {
                if (!launchSystemEqualizer(context, playerConnection?.audioSessionId ?: 0) { systemEqualizerLauncher.launch(it) }) {
                    Toast.makeText(context, systemEqualizerUnavailable, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
