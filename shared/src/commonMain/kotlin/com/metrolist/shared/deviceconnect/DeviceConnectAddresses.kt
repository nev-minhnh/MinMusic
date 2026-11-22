package com.metrolist.shared.deviceconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.device_connect_address_ipv6
import com.metrolist.shared.generated.resources.device_connect_address_lan
import com.metrolist.shared.generated.resources.device_connect_address_tailscale
import com.metrolist.shared.generated.resources.device_connect_addresses_empty
import com.metrolist.shared.generated.resources.device_connect_copy_address
import com.metrolist.shared.generated.resources.device_connect_qr_description
import com.metrolist.shared.generated.resources.device_connect_qr_hint
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.setText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * "This device's addresses": every address another device can dial (LAN, Tailscale, VPN) with the listening port and
 * a copy button, plus a QR code for the phone's camera when [showQrCode] is set (the PC).
 */
@Composable
fun DeviceConnectAddresses(
    state: DeviceConnectManager.State,
    showQrCode: Boolean,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val port = state.serverPort
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.reachableAddresses.isEmpty() || port <= 0) {
            Text(
                stringResource(Res.string.device_connect_addresses_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        state.reachableAddresses.forEach { address ->
            val endpoint = formatDeviceConnectEndpoint(address, port)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        stringResource(
                            when (deviceConnectAddressRank(address)) {
                                1 -> Res.string.device_connect_address_tailscale
                                2 -> Res.string.device_connect_address_ipv6
                                else -> Res.string.device_connect_address_lan
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { scope.launch { clipboard.setText(endpoint) } }) {
                    Icon(
                        MetrolistIcons.Outlined.ContentCopy,
                        contentDescription = stringResource(Res.string.device_connect_copy_address, endpoint),
                    )
                }
            }
        }
        if (showQrCode) {
            val link =
                remember(state.reachableAddresses, port, state.localDeviceName) {
                    buildDeviceConnectLink(state.reachableAddresses, port, state.localDeviceName)
                }
            if (link != null) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    DeviceConnectQrCode(
                        text = link,
                        contentDescription = stringResource(Res.string.device_connect_qr_description),
                        modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    )
                }
                Text(
                    stringResource(Res.string.device_connect_qr_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
