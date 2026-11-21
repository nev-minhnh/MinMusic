package com.metrolist.android.playlistimport

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.metrolist.shared.ui.components.Dialog

@Composable
internal fun ProviderLoginWindow(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
                // Sign-in pages take passwords: keep this window out of screenshots and recents.
                securePolicy = SecureFlagPolicy.SecureOn,
            ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}
