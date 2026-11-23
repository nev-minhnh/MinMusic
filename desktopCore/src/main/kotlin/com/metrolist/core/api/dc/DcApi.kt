package com.metrolist.core.api.dc

import com.metrolist.core.api.jsonOf
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.double
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.notFound
import com.metrolist.core.server.requireBool
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.topic
import com.metrolist.core.server.unsupported
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.deviceconnect.WindowsFirewallCore
import com.metrolist.shared.deviceconnect.DcSyncState
import com.metrolist.shared.deviceconnect.DeviceConnectManager
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** api.md 3.14. The state mapping and the pairing rules are pure (DcStateMapper, DcPairingRules). */
object DcApi {
    private val manager: DeviceConnectManager get() = DesktopDeviceConnect.manager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val SAMPLE_MS = 250L
    private const val SETTLE_MS = 5_000L

    val handlers: Map<String, Handler> =
        mapOf(
            "dc.state" to handler { _, _ -> stateNow() },
            "dc.enable" to handler { params, _ -> enable(params.requireBool("on")) },
            "dc.setPort" to handler { params, _ -> setPort(params.double("port")) },
            "dc.requireApproval" to
                handler { _, _ ->
                    // v1 always requires approval for code pairing; there is no setting to flip (api.md, M3-4a section).
                    unsupported("Device Connect always requires approval to pair; this cannot be turned off")
                },
            "dc.connectLink" to handler { params, _ -> connectLink(params.requireStr("link")) },
            "dc.confirmSas" to handler { params, _ -> confirmSas(params.requireStr("requestId"), params.requireBool("match")) },
            "dc.accept" to handler { params, _ -> accept(params.requireStr("requestId")) },
            "dc.reject" to handler { params, _ -> reject(params.requireStr("requestId")) },
            "dc.forget" to handler { params, _ -> forget(params.requireStr("deviceId")) },
            "dc.transferTo" to handler { params, _ -> transferTo(params.requireStr("deviceId")) },
            "dc.attach" to handler { params, _ -> attach(params.requireStr("deviceId")) },
            "dc.release" to handler { params, _ -> release(params.requireStr("deviceId")) },
            "dc.openFirewallSettings" to
                handler { _, _ ->
                    WindowsFirewallCore.openAllowedApps()
                    emptyResult()
                },
        )

    // --- state ---------------------------------------------------------------------------------------------------

    private val firewall = MutableStateFlow(DcStateMapper.firewallName(WindowsFirewallCore.lastKnown()))
    @Volatile private var firewallLoaded = false

    /** Reads the firewall rules off the request path (PowerShell takes seconds); the status is cached 60 s inside. */
    private fun refreshFirewall(force: Boolean) {
        if (!force && firewallLoaded) return
        firewallLoaded = true
        scope.launch { firewall.value = DcStateMapper.firewallName(WindowsFirewallCore.status(refresh = force)) }
    }

    private fun map(
        state: DeviceConnectManager.State,
        sync: DcSyncState,
        firewallName: String,
    ): JsonElement = DcStateMapper.map(state, sync, firewallName, Settings.shared.deviceConnectPort)

    private suspend fun stateNow(): JsonElement {
        backendReady()
        refreshFirewall(force = false)
        return map(manager.state.value, manager.remote.state.value, firewall.value)
    }

    /** `dc.snapshot`, then `dc.state` (sampled 250 ms) on any manager, playback-session or firewall change, and `dc.request`. */
    @OptIn(FlowPreview::class)
    val dcTopic: Topic =
        topic { _, emit ->
            backendReady()
            refreshFirewall(force = false)
            val requests = DcRequestDiff()
            emit("dc.snapshot", map(manager.state.value, manager.remote.state.value, firewall.value))
            for (request in requests.next(manager.state.value)) emit("dc.request", requestJson(request))
            // Requests are rare and must not wait for the sampler: watch them on the raw flow.
            val watcher =
                scope.launch {
                    manager.state.collect { state -> for (request in requests.next(state)) emit("dc.request", requestJson(request)) }
                }
            try {
                combine(manager.state, manager.remote.state, firewall) { state, sync, fw -> map(state, sync, fw) }
                    .sample(SAMPLE_MS)
                    .collect { emit("dc.state", it) }
            } finally {
                watcher.cancel()
            }
        }

    private fun requestJson(request: DcRequestEvent): JsonElement =
        jsonOf(
            "requestId" to JsonPrimitive(request.requestId),
            "peer" to JsonPrimitive(request.peer),
            "sas" to JsonPrimitive(request.sas),
        )

    // --- enable / port / link ------------------------------------------------------------------------------------

    private suspend fun enable(on: Boolean): JsonElement {
        backendReady()
        if (on) {
            // suspend fun enable(context: Any?) (DeviceConnectManager.kt:262); v1 passes Unit.
            manager.enable(Unit)
            refreshFirewall(force = true)
        } else {
            // fun disable() (:424) returns at once and tears down in the background: wait so dc.state is already off.
            manager.disable()
            withTimeoutOrNull(SETTLE_MS) { manager.state.first { !it.enabled } }
        }
        return emptyResult()
    }

    private suspend fun setPort(raw: Double?): JsonElement {
        val port = DcPortRange.parse(raw) ?: badRequest("port must be an integer in 1024..65535")
        backendReady()
        val before = manager.state.value
        // fun updateListeningPort(port: Int) (:353): stores the setting and restarts the listener when enabled.
        manager.updateListeningPort(port)
        if (before.enabled) {
            withTimeoutOrNull(SETTLE_MS) {
                manager.state.first { it.enabled && it.serverRunning && (it.serverPort == port || (it.fixedPortUnavailable && it.serverPort != before.serverPort)) }
            }
        }
        return emptyResult()
    }

    private suspend fun connectLink(link: String): JsonElement {
        backendReady()
        // fun offerConnectionLink(link: String): Boolean (:366); never connects, the offer surfaces as a `link:` request.
        if (!manager.offerConnectionLink(link.trim())) badRequest("not a device-connect link or no reachable endpoint")
        return emptyResult()
    }

    // --- pairing -------------------------------------------------------------------------------------------------

    private val pairingLock = Any()
    private var confirmed: Set<String> = emptySet()

    /** Runs one pure rule against the live manager state under a lock, then performs its effect. */
    private inline fun pairing(rule: (Set<String>, PairingView) -> PairingStep): JsonElement {
        val step =
            synchronized(pairingLock) {
                rule(confirmed, PairingView.of(manager.state.value)).also { confirmed = it.confirmed }
            }
        step.errorCode?.let { throw ApiException(it, step.errorMessage ?: it) }
        when (val effect = step.effect) {
            is PairingEffect.Approve -> manager.approvePairing(effect.requestId) // fun approvePairing(requestId: String) (:589)
            is PairingEffect.Reject -> manager.rejectPairing(effect.requestId) // fun rejectPairing(requestId: String) (:595)
            PairingEffect.AcceptLink -> manager.acceptConnectionLink() // fun acceptConnectionLink() (:374)
            PairingEffect.DismissLink -> manager.dismissConnectionLink() // fun dismissConnectionLink() (:380)
            null -> Unit
        }
        return emptyResult()
    }

    private suspend fun confirmSas(
        requestId: String,
        match: Boolean,
    ): JsonElement {
        backendReady()
        return pairing { c, v -> DcPairingRules.confirmSas(c, v, requestId, match) }
    }

    private suspend fun accept(requestId: String): JsonElement {
        backendReady()
        return pairing { c, v -> DcPairingRules.accept(c, v, requestId) }
    }

    private suspend fun reject(requestId: String): JsonElement {
        backendReady()
        return pairing { c, v -> DcPairingRules.reject(c, v, requestId) }
    }

    // --- devices and playback sessions ---------------------------------------------------------------------------

    private fun device(deviceId: String): DcDeviceRow =
        DcStateMapper.devices(manager.state.value).firstOrNull { it.id == deviceId } ?: notFound("device not found")

    private suspend fun forget(deviceId: String): JsonElement {
        backendReady()
        device(deviceId)
        manager.forget(deviceId) // suspend fun forget(deviceId: String) (:601)
        return emptyResult()
    }

    private suspend fun transferTo(deviceId: String): JsonElement {
        backendReady()
        if (!device(deviceId).online) badRequest("device offline")
        manager.remote.transferTo(deviceId) // fun transferTo(deviceId: String) (DeviceConnectRemote.kt:223)
        return emptyResult()
    }

    /**
     * Control the target's playback. `DeviceConnectRemote.discoverPlayback()` (:229) does NOT target one device: it
     * broadcasts an automatic ATTACH to every peer and only attaches when this device is idle and a peer is playing, so
     * it cannot honour a `deviceId`. `transferTo(deviceId)` has the right semantics: with an empty local queue it sends a
     * targeted ATTACH and starts controlling that device (startTransfer, :407-427), otherwise it hands the local queue over.
     */
    private suspend fun attach(deviceId: String): JsonElement {
        backendReady()
        if (!device(deviceId).online) badRequest("device offline")
        manager.remote.transferTo(deviceId)
        return emptyResult()
    }

    private suspend fun release(deviceId: String): JsonElement {
        backendReady()
        device(deviceId)
        manager.remote.leaveRemoteSession() // fun leaveRemoteSession() (:269)
        return emptyResult()
    }
}
