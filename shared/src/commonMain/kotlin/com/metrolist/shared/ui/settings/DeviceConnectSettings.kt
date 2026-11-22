/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.deviceconnect.DEVICE_CONNECT_DEFAULT_PORT
import com.metrolist.shared.deviceconnect.DcSyncState
import com.metrolist.shared.deviceconnect.DeviceConnectAddresses
import com.metrolist.shared.deviceconnect.DeviceConnectDirectConnection
import com.metrolist.shared.deviceconnect.DeviceConnectManager
import com.metrolist.shared.deviceconnect.DeviceConnectPlaybackStatus
import com.metrolist.shared.deviceconnect.decodeDeviceConnectManualEndpoints
import com.metrolist.shared.deviceconnect.deviceConnectDeviceMetadata
import com.metrolist.shared.deviceconnect.deviceConnectPeerStatus
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

data class DeviceConnectSettingsHost(
    val state: StateFlow<DeviceConnectManager.State>,
    val playbackState: StateFlow<DcSyncState>,
    val permissionRequired: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit,
    val onRequestPermission: () -> Unit = {},
    val onUpdateName: (String) -> Unit,
    val onRescan: () -> Unit,
    val onConnectDirect: (String) -> Unit,
    val onPair: (String) -> Unit,
    val onCancelPairing: (String) -> Unit,
    val onForget: (String) -> Unit,
    // MinMusic: direct (VPN) connections, pairing links and platform hints.
    val showQrCode: Boolean = false,
    val onUpdatePort: (Int) -> Unit = {},
    val onForgetManualEndpoint: (String) -> Unit = {},
    val onAcceptLink: () -> Unit = {},
    val onDismissLink: () -> Unit = {},
    val platformNotice: (@Composable () -> Unit)? = null,
)

@Composable
@NonRestartableComposable
internal fun SettingsUiRegistryBuilder.deviceConnectPage(host: DeviceConnectSettingsHost) {
    // State changes must rebuild the registry that owns these rows.
    val state by host.state.collectAsState()
    val playbackState by host.playbackState.collectAsState()
    val enabled = Settings.shared.deviceConnectEnabled
    val pairedIds = state.pairedDevices.mapTo(mutableSetOf()) { it.deviceId }
    val visiblePeerIds = state.peers.mapTo(mutableSetOf()) { it.id }
    val offlinePairedDevices = state.pairedDevices.filterNot { it.deviceId in visiblePeerIds }

    page(
        id = SettingsPageId.DEVICE_CONNECT,
        title = stringResource(Res.string.device_connect_title),
        subtitle = settingsPageSubtitle(stringResource(Res.string.settings_device_connect_overview), integrationStatus(enabled)),
        icon = MetrolistIcons.Outlined.Devices,
        tone = SettingsTone.BLUE,
    ) {
        // First, so the main switch does not disable it: accepting also turns Device Connect on.
        state.linkOffer?.let { offer ->
            group("deviceConnectLinkOffer", stringResource(Res.string.device_connect_link_offer_group)) {
                action(
                    id = "deviceConnectLinkAccept",
                    title = stringResource(Res.string.device_connect_link_offer_title, offer.deviceName.ifBlank { offer.address }),
                    description = offer.address,
                    valueLabel = stringResource(Res.string.connect),
                ) {
                    if (!enabled) host.onEnabledChange(true)
                    host.onAcceptLink()
                }
                action(
                    id = "deviceConnectLinkDismiss",
                    title = stringResource(Res.string.cancel),
                    onClick = host.onDismissLink,
                )
            }
        }
        group("deviceConnectMaster", "") {
            mainSwitch(
                id = "deviceConnectEnabled",
                title = stringResource(Res.string.device_connect_enable),
                checked = enabled,
                description = stringResource(Res.string.device_connect_explainer),
                onCheckedChange = host.onEnabledChange,
            )
        }
        group("deviceConnectSettings", stringResource(Res.string.settings), enabled = enabled) {
            text(
                id = "deviceConnectName",
                title = stringResource(Res.string.device_connect_name_label),
                description = Settings.shared.deviceConnectDeviceName.ifBlank { state.localDeviceName },
                value = Settings.shared.deviceConnectDeviceName,
                placeholder = stringResource(Res.string.device_connect_name_hint),
            ) { host.onUpdateName(it.trim()) }
            if (state.serverRunning) {
                action(
                    id = "deviceConnectThisDevice",
                    title = stringResource(Res.string.device_connect_this_device_label),
                    description = deviceConnectDeviceMetadata(state.localDeviceType),
                    enabled = false,
                ) {}
                action(
                    id = "deviceConnectListening",
                    title = stringResource(Res.string.device_connect_listening_title),
                    description =
                        if (state.fixedPortUnavailable) {
                            stringResource(Res.string.device_connect_port_busy, Settings.shared.deviceConnectPort, state.serverPort)
                        } else {
                            stringResource(Res.string.device_connect_listening, state.serverPort, state.reachableAddresses.size)
                        },
                    enabled = false,
                ) {}
            }
            if (host.permissionRequired) {
                action(
                    id = "deviceConnectPermission",
                    title = stringResource(Res.string.device_connect_grant_permission),
                    description = stringResource(Res.string.device_connect_permission_needed),
                    onClick = host.onRequestPermission,
                )
            }
            if (state.serverFailed) {
                action(
                    id = "deviceConnectRetry",
                    title = stringResource(Res.string.retry),
                    description = stringResource(Res.string.device_connect_server_failed),
                ) { host.onEnabledChange(true) }
            }
            action(
                id = "deviceConnectRescan",
                title = stringResource(Res.string.device_connect_rescan),
                description = if (state.discoveryFailed) stringResource(Res.string.device_connect_discovery_failed) else null,
                enabled = state.serverRunning,
                onClick = host.onRescan,
            )
            if (playbackState.error != null || (playbackState.sessionId != null && !playbackState.playbackAvailable)) {
                custom("deviceConnectPlaybackStatus", stringResource(Res.string.device_connect_title)) {
                    Column(Modifier.padding(16.dp)) { DeviceConnectPlaybackStatus(playbackState) }
                }
            }
            if (state.peers.isEmpty() && offlinePairedDevices.isEmpty() && !state.serverFailed && !state.discoveryFailed) {
                action(
                    id = "deviceConnectNoDevices",
                    title = stringResource(Res.string.device_connect_no_devices),
                    enabled = false,
                ) {}
            }
            state.peers.forEach { peer ->
                val paired = peer.id in pairedIds
                val pairingRequest = state.pairingRequests.firstOrNull { it.deviceId == peer.id }
                val status =
                    deviceConnectPeerStatus(
                        peer,
                        paired,
                        pairingRequest,
                        showConnected = true,
                        showAvailable =
                            peer.state != DeviceConnectManager.PeerState.DISCONNECTED,
                    )
                action(
                    id = "deviceConnectPeer:${peer.id}",
                    title = peer.name.ifBlank { peer.id },
                    description =
                        stringResource(
                            Res.string.device_connect_peer_details,
                            deviceConnectDeviceMetadata(peer.deviceType, peer.model),
                            status,
                        ),
                    valueLabel = stringResource(if (paired) Res.string.device_connect_forget else Res.string.device_connect_pair),
                    enabled =
                        paired || (
                            pairingRequest == null && peer.state != DeviceConnectManager.PeerState.CONNECTING &&
                                peer.state != DeviceConnectManager.PeerState.HANDSHAKING &&
                                peer.state != DeviceConnectManager.PeerState.CONNECTED &&
                                peer.state != DeviceConnectManager.PeerState.DISCONNECTED &&
                                peer.state != DeviceConnectManager.PeerState.ACCOUNT_MISMATCH
                        ),
                    confirmationMessage = if (paired) stringResource(Res.string.device_connect_forget_message) else null,
                ) {
                    if (paired) host.onForget(peer.id) else host.onPair(peer.id)
                }
                if (pairingRequest != null) {
                    action(
                        id = "deviceConnectCancel:${peer.id}",
                        title = stringResource(Res.string.cancel),
                        description = peer.name.ifBlank { peer.id },
                    ) { host.onCancelPairing(pairingRequest.requestId) }
                }
            }
            offlinePairedDevices.forEach { device ->
                action(
                    id = "deviceConnectOffline:${device.deviceId}",
                    title = device.deviceName.ifBlank { device.deviceId },
                    description =
                        stringResource(
                            Res.string.device_connect_peer_details,
                            deviceConnectDeviceMetadata(device.deviceType, device.model),
                            stringResource(Res.string.device_connect_offline),
                        ),
                    valueLabel = stringResource(Res.string.device_connect_forget),
                    confirmationMessage = stringResource(Res.string.device_connect_forget_message),
                ) { host.onForget(device.deviceId) }
            }
        }
        group("deviceConnectAddresses", stringResource(Res.string.device_connect_addresses_title), enabled = enabled) {
            custom("deviceConnectAddressList", stringResource(Res.string.device_connect_addresses_title), enabled = state.serverRunning) {
                if (state.serverRunning) DeviceConnectAddresses(state, host.showQrCode)
            }
            text(
                id = "deviceConnectPort",
                title = stringResource(Res.string.device_connect_port_title),
                description = stringResource(Res.string.device_connect_port_description, DEVICE_CONNECT_DEFAULT_PORT),
                value = Settings.shared.deviceConnectPort.toString(),
                placeholder = DEVICE_CONNECT_DEFAULT_PORT.toString(),
            ) { value -> value.trim().toIntOrNull()?.let(host.onUpdatePort) }
            host.platformNotice?.let { notice ->
                custom("deviceConnectPlatformNotice", stringResource(Res.string.device_connect_addresses_title)) { notice() }
            }
        }
        group("deviceConnectAdvanced", stringResource(Res.string.advanced), enabled = enabled) {
            custom("deviceConnectDirect", stringResource(Res.string.device_connect_direct_title), enabled = state.serverRunning) {
                if (enabled) {
                    Column(Modifier.padding(16.dp)) {
                        if (state.serverRunning) {
                            DeviceConnectDirectConnection(state, host.onConnectDirect)
                        } else {
                            Text(stringResource(Res.string.device_connect_direct_title))
                        }
                    }
                }
            }
            decodeDeviceConnectManualEndpoints(Settings.shared.deviceConnectManualEndpoints).forEach { endpoint ->
                action(
                    id = "deviceConnectManual:$endpoint",
                    title = endpoint,
                    description = stringResource(Res.string.device_connect_manual_saved),
                    valueLabel = stringResource(Res.string.device_connect_forget),
                ) { host.onForgetManualEndpoint(endpoint) }
            }
            action(
                id = "deviceConnectInfo",
                title = stringResource(Res.string.device_connect_beta),
                description = if (state.anonymous) stringResource(Res.string.device_connect_signed_out_pairing) else null,
                enabled = false,
            ) {}
        }
    }
}
