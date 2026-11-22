package com.metrolist.shared.deviceconnect

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter

internal fun DeviceConnectManager.PeerState.isLiveConnectionState(): Boolean =
    this == DeviceConnectManager.PeerState.CONNECTING ||
        this == DeviceConnectManager.PeerState.HANDSHAKING ||
        this == DeviceConnectManager.PeerState.CONNECTED

internal fun peerAfterFailedDeviceConnectAttempt(
    current: DeviceConnectManager.PeerDevice?,
    attemptedHost: String,
    attemptedPort: Int,
    attemptedServiceName: String,
    failedEndpointState: DeviceConnectManager.PeerState? = null,
): DeviceConnectManager.PeerDevice? =
    when {
        current == null -> {
            null
        }

        current.state != DeviceConnectManager.PeerState.CONNECTING -> {
            current
        }

        current.host == attemptedHost &&
            current.port == attemptedPort &&
            (current.serviceName == attemptedServiceName || current.serviceName.isBlank()) -> {
            failedEndpointState?.let { current.copy(state = it) }
        }

        else -> {
            current.copy(state = DeviceConnectManager.PeerState.DISCOVERED)
        }
    }

internal fun peerAfterLostDeviceConnectEndpoint(
    current: DeviceConnectManager.PeerDevice,
    lost: DiscoveredDevice,
): DeviceConnectManager.PeerDevice? {
    val exactEndpoint =
        current.serviceName == lost.serviceName &&
            current.host == lost.host &&
            current.port == lost.port
    if (!exactEndpoint) return current
    return peerAfterDeviceConnectDiscoveryStopped(current)
}

internal fun peerAfterDeviceConnectDiscoveryStopped(current: DeviceConnectManager.PeerDevice): DeviceConnectManager.PeerDevice? =
    if (current.state.isLiveConnectionState()) current.copy(serviceName = "") else null

internal fun hasAvailableDeviceConnectEndpoint(peer: DeviceConnectManager.PeerDevice): Boolean =
    peer.serviceName.isNotBlank() && peer.host.isNotBlank() && peer.port in 1..65535

internal fun isValidDeviceId(value: String): Boolean =
    value.length in 1..128 &&
        value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

internal fun deviceConnectWebSocketUrl(
    host: String,
    port: Int,
): String {
    val unwrappedHost = host.removePrefix("[").removeSuffix("]")
    val authorityHost =
        if (':' in unwrappedHost) {
            "[${unwrappedHost.replace("%", "%25")}]"
        } else {
            unwrappedHost
        }
    return "ws://$authorityHost:$port/connect"
}

internal fun selectDeviceConnectHost(
    hosts: List<String>,
    localHost: String? = getLocalIpV4Address(),
): String? {
    val cleanHosts =
        hosts
            .map { it.removePrefix("[").removeSuffix("]") }
            .filter(::isDeviceConnectLanHost)
    if (cleanHosts.isEmpty()) return null
    val localPrefix = localHost?.ipv424Prefix()
    return localPrefix?.let { prefix -> cleanHosts.firstOrNull { it.ipv424Prefix() == prefix } } ?: cleanHosts.first()
}

internal fun isDeviceConnectLanHost(value: String): Boolean {
    val host = value.substringBefore('%')
    if ('%' in value) {
        val zone = value.substringAfter('%')
        if (':' !in host || zone.isEmpty() || zone.any { !it.isLetterOrDigit() && it !in "._-" }) return false
    }
    val ipv4 = host.split('.')
    if (
        ipv4.size == 4 &&
        ipv4.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                (part == "0" || !part.startsWith('0')) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    ) {
        val first = ipv4[0].toInt()
        val second = ipv4[1].toInt()
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168) ||
            (first == 100 && second in 64..127)
    }
    if (':' !in host || host.any { it !in "0123456789abcdefABCDEF:" }) return false
    val halves = host.split("::")
    if (halves.size > 2) return false
    val hextets = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
    if (hextets.any { it.length !in 1..4 }) return false
    if (if (halves.size == 2) hextets.size !in 1..7 else hextets.size != 8) return false
    val firstHextet = host.substringBefore(':').toIntOrNull(16) ?: return false
    return firstHextet and 0xfe00 == 0xfc00 || firstHextet and 0xffc0 == 0xfe80
}

private fun String.ipv424Prefix(): String? {
    val parts = split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
    return parts.take(3).joinToString(".")
}

internal data class DeviceConnectEndpoint(
    val host: String,
    val port: Int,
)

internal fun parseDeviceConnectEndpoint(value: String): DeviceConnectEndpoint? {
    val input = value.trim()
    val host: String
    val portText: String
    if (input.startsWith("[")) {
        val end = input.indexOf("]:")
        if (end < 0) return null
        host = input.substring(1, end)
        if (':' !in host) return null
        portText = input.substring(end + 2)
    } else {
        if (input.count { it == ':' } != 1) return null
        host = input.substringBefore(':')
        portText = input.substringAfter(':')
    }
    if (!isDeviceConnectLanHost(host) || portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return DeviceConnectEndpoint(host, port)
}

/** The fixed listening port used by default, so peers across a VPN can dial `IP:port` without looking it up. */
const val DEVICE_CONNECT_DEFAULT_PORT = 47821

internal const val DEVICE_CONNECT_MIN_FIXED_PORT = 1024
internal const val DEVICE_CONNECT_LINK_PREFIX = "metrolist://device-connect"
private const val MAX_MANUAL_ENDPOINTS = 8
private const val MAX_LINK_ENDPOINTS = 4

/** LAN first, then Tailscale's 100.64.0.0/10, then IPv6. */
internal fun deviceConnectAddressRank(host: String): Int =
    when {
        ':' in host -> 2
        host.startsWith("100.") -> 1
        else -> 0
    }

internal fun formatDeviceConnectEndpoint(
    host: String,
    port: Int,
): String = if (':' in host) "[$host]:$port" else "$host:$port"

/** A typed or remembered connection target: an allowed IP literal or a hostname, plus a port. */
internal data class DeviceConnectAddressInput(
    val host: String,
    val port: Int,
    val isHostname: Boolean,
) {
    val display: String get() = formatDeviceConnectEndpoint(host, port)
}

/**
 * Accepts `IPv4:port`, `[IPv6]:port`, a bare allowed IP, `hostname` or `hostname:port` (e.g. Tailscale MagicDNS
 * `pc-name` or `pc-name.tailnet.ts.net`). A missing port means [defaultPort]. Public IP literals are rejected here;
 * hostnames are checked against the same allow-list after resolution.
 */
internal fun parseDeviceConnectAddressInput(
    value: String,
    defaultPort: Int = DEVICE_CONNECT_DEFAULT_PORT,
): DeviceConnectAddressInput? {
    val input = value.trim()
    if (input.isEmpty()) return null
    parseDeviceConnectEndpoint(input)?.let { return DeviceConnectAddressInput(it.host, it.port, isHostname = false) }
    val bare = input.removePrefix("[").removeSuffix("]")
    if (isDeviceConnectLanHost(bare)) return DeviceConnectAddressInput(bare, defaultPort, isHostname = false)
    val colons = input.count { it == ':' }
    if (colons > 1 || input.startsWith("[")) return null
    val host = input.substringBefore(':')
    val port =
        if (colons == 0) {
            defaultPort
        } else {
            val portText = input.substringAfter(':')
            if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
            portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
    if (!isDeviceConnectHostname(host)) return null
    return DeviceConnectAddressInput(host.removeSuffix(".").lowercase(), port, isHostname = true)
}

/** A DNS name (letters, digits and hyphens per label). Dotted numbers are never names, so public IPs stay blocked. */
internal fun isDeviceConnectHostname(value: String): Boolean {
    val host = value.removeSuffix(".")
    if (host.isEmpty() || host.length > 253) return false
    val labels = host.split('.')
    if (labels.all { label -> label.isNotEmpty() && label.all { it in '0'..'9' } }) return false
    return labels.all { label ->
        label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
            label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }
}

/**
 * Resolves a hostname and keeps only addresses the direct-connection allow-list accepts (private IPv4, 100.64.0.0/10,
 * local IPv6), preferring IPv4. Returns null when nothing allowed remains, so public addresses are never dialled.
 */
internal suspend fun resolveDeviceConnectAddressInput(input: DeviceConnectAddressInput): DeviceConnectEndpoint? {
    if (!input.isHostname) return DeviceConnectEndpoint(input.host, input.port)
    val allowed =
        resolveDeviceConnectHostAddresses(input.host)
            .map { it.removePrefix("[").removeSuffix("]") }
            .filter(::isDeviceConnectLanHost)
    val host =
        allowed.firstOrNull { ':' !in it }
            ?: allowed.firstOrNull { !it.lowercase().startsWith("fe80") }
            ?: return null
    return DeviceConnectEndpoint(host, input.port)
}

internal fun decodeDeviceConnectManualEndpoints(value: String): List<String> =
    value
        .split(',')
        .mapNotNull { parseDeviceConnectAddressInput(it)?.display }
        .distinct()
        .take(MAX_MANUAL_ENDPOINTS)

/** Puts [endpoint] first so the most recent manual target is retried first. */
internal fun rememberDeviceConnectManualEndpoint(
    stored: String,
    endpoint: String,
): String = (listOf(endpoint) + decodeDeviceConnectManualEndpoints(stored)).distinct().take(MAX_MANUAL_ENDPOINTS).joinToString(",")

internal fun forgetDeviceConnectManualEndpoint(
    stored: String,
    endpoint: String,
): String = decodeDeviceConnectManualEndpoints(stored).filter { it != endpoint }.joinToString(",")

/** A pairing link shown as a QR code: this device's dialable IPv4 endpoints and its name. */
internal data class DeviceConnectLink(
    val endpoints: List<DeviceConnectEndpoint>,
    val deviceName: String,
)

internal fun buildDeviceConnectLink(
    addresses: List<String>,
    port: Int,
    deviceName: String,
): String? {
    val endpoints =
        addresses
            .filter { ':' !in it && isDeviceConnectLanHost(it) }
            .take(MAX_LINK_ENDPOINTS)
            .map { formatDeviceConnectEndpoint(it, port) }
    if (endpoints.isEmpty() || port !in 1..65535) return null
    val name = sanitizeDeviceName(deviceName).take(32).encodeURLParameter()
    return "$DEVICE_CONNECT_LINK_PREFIX?a=${endpoints.joinToString(",")}&n=$name"
}

/** Parses [buildDeviceConnectLink] output. Only allowed IP literals are accepted from a link, never hostnames. */
internal fun parseDeviceConnectLink(value: String): DeviceConnectLink? {
    val input = value.trim()
    if (!input.startsWith(DEVICE_CONNECT_LINK_PREFIX, ignoreCase = true)) return null
    val parameters =
        input
            .substringAfter('?', "")
            .split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                if (key.isEmpty()) null else key to part.substringAfter('=')
            }.toMap()
    val endpoints =
        parameters["a"]
            .orEmpty()
            .split(',')
            .take(MAX_LINK_ENDPOINTS)
            .mapNotNull { parseDeviceConnectEndpoint(it) }
    if (endpoints.isEmpty()) return null
    val name = parameters["n"]?.let { runCatching { it.decodeURLQueryComponent() }.getOrNull() }.orEmpty()
    return DeviceConnectLink(endpoints, sanitizeDeviceName(name))
}

/** Prefers an endpoint on this device's own /24 (same LAN), then Tailscale when this device is on it. */
internal fun selectDeviceConnectLinkEndpoint(
    endpoints: List<DeviceConnectEndpoint>,
    localAddresses: List<String>,
): DeviceConnectEndpoint? {
    val localPrefixes = localAddresses.mapNotNull { it.ipv424Prefix() }.toSet()
    val hasCgnat = localAddresses.any { deviceConnectAddressRank(it) == 1 }
    return endpoints.firstOrNull { endpoint -> endpoint.host.ipv424Prefix()?.let { it in localPrefixes } == true }
        ?: endpoints.firstOrNull { hasCgnat && deviceConnectAddressRank(it.host) == 1 }
        ?: endpoints.firstOrNull()
}
