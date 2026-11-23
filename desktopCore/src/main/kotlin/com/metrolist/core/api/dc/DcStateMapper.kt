package com.metrolist.core.api.dc

import com.metrolist.desktop.deviceconnect.CoreFirewallStatus
import com.metrolist.shared.deviceconnect.DEVICE_CONNECT_DEFAULT_PORT
import com.metrolist.shared.deviceconnect.DcSyncState
import com.metrolist.shared.deviceconnect.DeviceConnectDeviceType
import com.metrolist.shared.deviceconnect.DeviceConnectManager
import com.metrolist.shared.deviceconnect.deviceConnectLinkFor
import com.metrolist.shared.deviceconnect.deviceConnectQrSvg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One row of `dc.state.devices` (api.md 3.14). */
data class DcDeviceRow(
    val id: String,
    val name: String,
    val type: String,
    val online: Boolean,
    val paired: Boolean,
    val accountBound: Boolean,
)

/** Pure mapping from the Device Connect manager state to the api.md 3.14 `dc.state` object. */
object DcStateMapper {
    private val ONLINE_STATES =
        setOf(
            DeviceConnectManager.PeerState.DISCOVERED,
            DeviceConnectManager.PeerState.CONNECTING,
            DeviceConnectManager.PeerState.HANDSHAKING,
            DeviceConnectManager.PeerState.CONNECTED,
        )

    fun firewallName(status: CoreFirewallStatus): String =
        when (status) {
            CoreFirewallStatus.ALLOWED -> "allowed"
            CoreFirewallStatus.BLOCKED -> "blocked"
            CoreFirewallStatus.NO_RULE -> "no_rule"
            CoreFirewallStatus.UNKNOWN -> "unknown"
        }

    /** The union of the paired devices (`paired = true`) and the discovered peers, keyed by id; paired ones first. */
    fun devices(state: DeviceConnectManager.State): List<DcDeviceRow> {
        val peers = state.peers.associateBy { it.id }
        val rows = LinkedHashMap<String, DcDeviceRow>()
        for (paired in state.pairedDevices) {
            val peer = peers[paired.deviceId]
            rows[paired.deviceId] =
                DcDeviceRow(
                    id = paired.deviceId,
                    name = peer?.name?.takeIf { it.isNotBlank() } ?: paired.deviceName,
                    type = (peer?.deviceType?.takeIf { it != DeviceConnectDeviceType.UNKNOWN } ?: paired.deviceType).name.lowercase(),
                    online = peer != null && peer.state in ONLINE_STATES,
                    paired = true,
                    accountBound = accountBound(state, peer),
                )
        }
        for (peer in state.peers) {
            if (peer.id in rows) continue
            rows[peer.id] =
                DcDeviceRow(
                    id = peer.id,
                    name = peer.name,
                    type = peer.deviceType.name.lowercase(),
                    online = peer.state in ONLINE_STATES,
                    paired = false,
                    accountBound = accountBound(state, peer),
                )
        }
        return rows.values.toList()
    }

    private fun accountBound(
        state: DeviceConnectManager.State,
        peer: DeviceConnectManager.PeerDevice?,
    ): Boolean = peer?.state != DeviceConnectManager.PeerState.ACCOUNT_MISMATCH && !state.anonymous

    /** `{peerId, role}` while a playback session exists, else null. When this device plays, the peer is the controller. */
    fun session(
        state: DeviceConnectManager.State,
        sync: DcSyncState,
    ): Pair<String, String>? {
        if (sync.sessionId == null) return null
        val isPlayer = sync.targetId == state.localDeviceId
        val peerId =
            if (isPlayer) {
                sync.controllerIds.firstOrNull { it != state.localDeviceId } ?: sync.targetId.orEmpty()
            } else {
                sync.targetId.orEmpty()
            }
        return peerId to if (isPlayer) "player" else "controller"
    }

    @Volatile private var lastQr: Pair<String, String>? = null

    /** QR encoding is not free and the topic maps every 250 ms: keep the last (link, svg). */
    private fun qrFor(link: String): String {
        lastQr?.let { (text, svg) -> if (text == link) return svg }
        val svg = deviceConnectQrSvg(link).orEmpty()
        lastQr = link to svg
        return svg
    }

    /** The port to show: the actual listener port, else the configured one (disabled), else the default. */
    fun displayPort(
        state: DeviceConnectManager.State,
        configuredPort: Int,
    ): Int =
        when {
            state.serverPort in 1..65535 -> state.serverPort
            configuredPort in 1024..65535 -> configuredPort
            else -> DEVICE_CONNECT_DEFAULT_PORT
        }

    fun map(
        state: DeviceConnectManager.State,
        sync: DcSyncState,
        firewall: String,
        configuredPort: Int = DEVICE_CONNECT_DEFAULT_PORT,
    ): JsonElement {
        val port = displayPort(state, configuredPort)
        val link = if (state.enabled) deviceConnectLinkFor(state.reachableAddresses, port, state.localDeviceName).orEmpty() else ""
        val session = session(state, sync)
        return buildJsonObject {
            put("enabled", state.enabled)
            put("port", port)
            put("addresses", JsonArray(if (state.enabled) state.reachableAddresses.map { JsonPrimitive("$it:$port") } else emptyList()))
            put("link", link)
            put("qrSvg", if (link.isEmpty()) "" else qrFor(link))
            put("firewall", firewall)
            put(
                "devices",
                JsonArray(
                    devices(state).map {
                        buildJsonObject {
                            put("id", it.id)
                            put("name", it.name)
                            put("type", it.type)
                            put("online", it.online)
                            put("paired", it.paired)
                            put("accountBound", it.accountBound)
                        }
                    },
                ),
            )
            if (session != null) {
                put(
                    "session",
                    buildJsonObject {
                        put("peerId", session.first)
                        put("role", session.second)
                    },
                )
            }
            // <!-- M3-4a --> additive: the configured fixed port was busy, so the listener fell back to a free one.
            put("fixedPortUnavailable", state.fixedPortUnavailable)
        }
    }
}
