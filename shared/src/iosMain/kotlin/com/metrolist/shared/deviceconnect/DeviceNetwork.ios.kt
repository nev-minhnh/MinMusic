package com.metrolist.shared.deviceconnect

internal actual fun getLocalIpV4Addresses(): List<String> = emptyList()

internal actual fun getDeviceConnectReachableAddresses(): List<String> = emptyList()

internal actual suspend fun resolveDeviceConnectHostAddresses(host: String): List<String> = emptyList()
