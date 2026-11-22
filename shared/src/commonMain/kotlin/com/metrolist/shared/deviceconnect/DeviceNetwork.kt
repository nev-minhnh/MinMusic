/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Resolve the device's own LAN IPv4 address (used to advertise the local server).
 */

package com.metrolist.shared.deviceconnect

internal expect fun getLocalIpV4Addresses(): List<String>

fun getLocalIpV4Address(): String? = getLocalIpV4Addresses().firstOrNull()

/**
 * Every local address another device can dial directly, LAN first: private IPv4 (including OpenVPN and WireGuard
 * tunnels), Tailscale's 100.64.0.0/10 and unique-local IPv6. Unlike discovery, this includes VPN adapters.
 */
internal expect fun getDeviceConnectReachableAddresses(): List<String>

/** Resolves [host], such as a Tailscale MagicDNS name, to IP literals; empty when it does not resolve. */
internal expect suspend fun resolveDeviceConnectHostAddresses(host: String): List<String>
