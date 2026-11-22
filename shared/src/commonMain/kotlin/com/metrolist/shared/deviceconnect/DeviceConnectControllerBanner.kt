package com.metrolist.shared.deviceconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.device_connect_controlling
import com.metrolist.shared.generated.resources.device_connect_controlling_hint
import com.metrolist.shared.generated.resources.device_connect_disconnect
import com.metrolist.shared.generated.resources.device_connect_play_here
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

/** The device this one remote-controls through Device Connect, with the two ways out of that mode. */
@Immutable
data class DeviceConnectControlledTarget(
    val deviceId: String,
    val name: String,
    val onPlayHere: () -> Unit,
    val onDisconnect: () -> Unit,
)

/** Non-null while this device follows (remote-controls) another device's playback. */
@Composable
fun rememberDeviceConnectControlledTarget(manager: DeviceConnectManager): DeviceConnectControlledTarget? {
    val state by manager.state.collectAsState()
    val sync by manager.remote.state.collectAsState()
    val targetId = sync.targetId
    if (sync.sessionId == null || targetId == null || targetId == state.localDeviceId) return null
    val name =
        state.peers.firstOrNull { it.id == targetId }?.name?.takeIf { it.isNotBlank() }
            ?: state.pairedDevices.firstOrNull { it.deviceId == targetId }?.deviceName?.takeIf { it.isNotBlank() }
            ?: targetId
    return remember(manager, targetId, name) {
        DeviceConnectControlledTarget(
            deviceId = targetId,
            name = name,
            onPlayHere = { manager.remote.transferTo(manager.state.value.localDeviceId) },
            onDisconnect = manager.remote::leaveRemoteSession,
        )
    }
}

/**
 * "Controlling: <PC>" with "Play on this device" (pulls playback back) and "Disconnect" (leaves the other device
 * playing). The player's controls drive the other device meanwhile.
 */
@Composable
fun DeviceConnectControllerBanner(
    target: DeviceConnectControlledTarget,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(MetrolistIcons.Outlined.Devices, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.device_connect_controlling, target.name),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(Res.string.device_connect_controlling_hint),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = target.onDisconnect) { Text(stringResource(Res.string.device_connect_disconnect)) }
                TextButton(onClick = target.onPlayHere) { Text(stringResource(Res.string.device_connect_play_here)) }
            }
        }
    }
}
