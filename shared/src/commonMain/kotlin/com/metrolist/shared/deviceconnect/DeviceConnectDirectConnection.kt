package com.metrolist.shared.deviceconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.connect
import com.metrolist.shared.generated.resources.device_connect_direct_address_or_host
import com.metrolist.shared.generated.resources.device_connect_direct_failed
import com.metrolist.shared.generated.resources.device_connect_direct_hint_vpn
import com.metrolist.shared.generated.resources.device_connect_direct_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceConnectDirectConnection(
    state: DeviceConnectManager.State,
    onConnect: (String) -> Unit,
) {
    if (!state.serverRunning) return
    var expanded by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("") }
    val endpoint = parseDeviceConnectAddressInput(address) ?: parseDeviceConnectLink(address)
    val connecting =
        state.peers.any {
            it.state == DeviceConnectManager.PeerState.CONNECTING || it.state == DeviceConnectManager.PeerState.HANDSHAKING
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.device_connect_direct_title))
        }
        if (expanded) {
            Text(
                stringResource(Res.string.device_connect_direct_hint_vpn, DEVICE_CONNECT_DEFAULT_PORT),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(Res.string.device_connect_direct_address_or_host)) },
                singleLine = true,
                isError = address.isNotBlank() && endpoint == null,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { onConnect(address) },
                enabled = endpoint != null && !connecting && state.pairingRequests.isEmpty(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.connect))
            }
        }
        if (state.directConnectionFailed) {
            Text(stringResource(Res.string.device_connect_direct_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}
