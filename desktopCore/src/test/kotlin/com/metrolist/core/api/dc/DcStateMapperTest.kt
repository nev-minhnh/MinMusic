package com.metrolist.core.api.dc

import com.metrolist.desktop.deviceconnect.CoreFirewallStatus
import com.metrolist.shared.deviceconnect.DcSyncState
import com.metrolist.shared.deviceconnect.DeviceConnectDeviceType
import com.metrolist.shared.deviceconnect.DeviceConnectManager.LinkOffer
import com.metrolist.shared.deviceconnect.DeviceConnectManager.PairedDevice
import com.metrolist.shared.deviceconnect.DeviceConnectManager.PairingRequest
import com.metrolist.shared.deviceconnect.DeviceConnectManager.PeerDevice
import com.metrolist.shared.deviceconnect.DeviceConnectManager.PeerState
import com.metrolist.shared.deviceconnect.DeviceConnectManager.State
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DcStateMapperTest {
    private fun peer(
        id: String,
        state: PeerState,
        name: String = "peer-$id",
        type: DeviceConnectDeviceType = DeviceConnectDeviceType.ANDROID_PHONE,
    ) = PeerDevice(id = id, name = name, host = "192.168.1.9", port = 1, state = state, deviceType = type)

    private fun paired(
        id: String,
        name: String = "paired-$id",
    ) = PairedDevice(deviceId = id, deviceName = name, deviceType = DeviceConnectDeviceType.ANDROID_PHONE)

    private fun row(
        state: State,
        id: String,
    ) = DcStateMapper.devices(state).single { it.id == id }

    @Test
    fun devicesAreTheUnionOfPairedAndPeersById() {
        val state =
            State(
                pairedDevices = listOf(paired("a"), paired("b")),
                peers = listOf(peer("b", PeerState.CONNECTED, name = "B live"), peer("c", PeerState.DISCOVERED)),
            )
        val rows = DcStateMapper.devices(state)
        assertEquals(listOf("a", "b", "c"), rows.map { it.id })
        assertEquals(listOf(true, true, false), rows.map { it.paired })
        assertEquals("B live", row(state, "b").name, "the live peer name wins")
        assertEquals("paired-a", row(state, "a").name)
        assertEquals("android_phone", row(state, "a").type)
    }

    @Test
    fun onlineFollowsThePeerState() {
        val states =
            mapOf(
                PeerState.DISCOVERED to true,
                PeerState.CONNECTING to true,
                PeerState.HANDSHAKING to true,
                PeerState.CONNECTED to true,
                PeerState.ACCOUNT_MISMATCH to false,
                PeerState.ACCOUNT_UNRESOLVED to false,
                PeerState.DISCONNECTED to false,
                PeerState.ERROR to false,
            )
        for ((peerState, online) in states) {
            val state = State(pairedDevices = listOf(paired("a")), peers = listOf(peer("a", peerState)))
            assertEquals(online, row(state, "a").online, peerState.name)
        }
        // Paired but not seen on the network: offline.
        assertFalse(row(State(pairedDevices = listOf(paired("a"))), "a").online)
    }

    @Test
    fun accountBoundIsFalseOnMismatchOrAnonymous() {
        val ok = State(peers = listOf(peer("a", PeerState.CONNECTED)))
        assertTrue(row(ok, "a").accountBound)
        assertFalse(row(State(peers = listOf(peer("a", PeerState.ACCOUNT_MISMATCH))), "a").accountBound)
        assertFalse(row(ok.copy(anonymous = true), "a").accountBound)
        assertTrue(row(State(pairedDevices = listOf(paired("a"))), "a").accountBound)
    }

    @Test
    fun sessionRoleIsPlayerWhenThisDeviceIsTheTarget() {
        val state = State(localDeviceId = "me")
        assertNull(DcStateMapper.session(state, DcSyncState()))
        assertEquals("pixel" to "controller", DcStateMapper.session(state, DcSyncState(sessionId = "s", targetId = "pixel")))
        assertEquals(
            "tablet" to "player",
            DcStateMapper.session(state, DcSyncState(sessionId = "s", targetId = "me", controllerIds = setOf("me", "tablet"))),
        )
    }

    @Test
    fun disabledStateHasNoLinkNoQrAndTheConfiguredPort() {
        val json = DcStateMapper.map(State(), DcSyncState(), "unknown", configuredPort = 48765).jsonObject
        assertEquals(false, json["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(48765, json["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("", json["link"]!!.jsonPrimitive.content)
        assertEquals("", json["qrSvg"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), json["addresses"])
        assertNull(json["session"])
        assertEquals(false, json["fixedPortUnavailable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun enabledStateCarriesAddressesLinkAndQr() {
        val state =
            State(
                enabled = true,
                serverRunning = true,
                serverPort = 48911,
                localDeviceName = "MinMusic-PC",
                reachableAddresses = listOf("192.168.1.23", "10.0.0.5"),
                fixedPortUnavailable = true,
            )
        val json = DcStateMapper.map(state, DcSyncState(), DcStateMapper.firewallName(CoreFirewallStatus.NO_RULE), configuredPort = 1111).jsonObject
        assertEquals(48911, json["port"]!!.jsonPrimitive.content.toInt(), "the actual port, not the setting")
        assertEquals(listOf("192.168.1.23:48911", "10.0.0.5:48911"), json["addresses"]!!.jsonArray.map { it.jsonPrimitive.content })
        val link = json["link"]!!.jsonPrimitive.content
        assertTrue(link.startsWith("metrolist://device-connect?a=192.168.1.23:48911"), link)
        assertTrue(link.endsWith("&n=MinMusic-PC"), link)
        assertTrue(json["qrSvg"]!!.jsonPrimitive.content.startsWith("<svg"))
        assertEquals("no_rule", json["firewall"]!!.jsonPrimitive.content)
        assertEquals(true, json["fixedPortUnavailable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun firewallNames() {
        assertEquals("allowed", DcStateMapper.firewallName(CoreFirewallStatus.ALLOWED))
        assertEquals("blocked", DcStateMapper.firewallName(CoreFirewallStatus.BLOCKED))
        assertEquals("no_rule", DcStateMapper.firewallName(CoreFirewallStatus.NO_RULE))
        assertEquals("unknown", DcStateMapper.firewallName(CoreFirewallStatus.UNKNOWN))
    }

    @Test
    fun deviceRowsSerialiseAllSixFields() {
        val state = State(pairedDevices = listOf(paired("a")))
        val device: JsonObject = DcStateMapper.map(state, DcSyncState(), "unknown").jsonObject["devices"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "name", "type", "online", "paired", "accountBound"), device.keys)
    }

    @Test
    fun requestDiffEmitsPairingAndLinkRequestsOnce() {
        val diff = DcRequestDiff()
        val request = PairingRequest("r1", "d1", "Pixel", sas = "123456", requiresApproval = true, expiresAtEpochMs = 0)
        val silent = PairingRequest("r2", "d2", "Quiet", sas = "", requiresApproval = false, expiresAtEpochMs = 0)
        val first = diff.next(State(pairingRequests = listOf(request, silent)))
        assertEquals(listOf(DcRequestEvent("r1", "Pixel", "123456")), first)
        assertTrue(diff.next(State(pairingRequests = listOf(request))).isEmpty(), "already emitted")

        val offer = LinkOffer(deviceName = "Phone B", address = "192.168.1.50:48765")
        val withLink = diff.next(State(linkOffer = offer))
        assertEquals(1, withLink.size)
        assertEquals(linkRequestId("192.168.1.50:48765"), withLink.single().requestId)
        assertEquals("", withLink.single().sas)
        assertEquals("Phone B", withLink.single().peer)
        assertTrue(diff.next(State(linkOffer = offer)).isEmpty())
        // Gone and back again: a new request.
        diff.next(State())
        assertEquals(1, diff.next(State(pairingRequests = listOf(request))).size)
    }

    @Test
    fun linkRequestIdIsStableTwelveHexChars() {
        val id = linkRequestId("192.168.1.50:48765")
        assertTrue(Regex("^link:[0-9a-f]{12}$").matches(id), id)
        assertEquals(id, linkRequestId("192.168.1.50:48765"))
        assertTrue(id != linkRequestId("192.168.1.51:48765"))
    }
}
