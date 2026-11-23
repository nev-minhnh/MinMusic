package com.metrolist.shared.deviceconnect

/*
 * Public wrappers over the internal Device Connect link builder and QR encoder, for the desktop core (api.md 3.14).
 * Additive only: no behaviour of the existing code changes. The QR is the v1 encoder, so the code is byte-identical.
 */

private const val QR_QUIET_ZONE_MODULES = 4

/** The `metrolist://device-connect?a=ip:port,...&n=name` pairing link, or null when no LAN address can be advertised. */
fun deviceConnectLinkFor(
    addresses: List<String>,
    port: Int,
    deviceName: String,
): String? = buildDeviceConnectLink(addresses, port, deviceName)

/**
 * [text] as a QR code in a single-path SVG: black modules on white, a 4-module quiet zone, the viewBox in modules,
 * `shape-rendering="crispEdges"`. Null when the text does not fit the encoder (same limit as the v1 screen).
 */
fun deviceConnectQrSvg(text: String): String? = deviceConnectQrMatrixToSvg(encodeDeviceConnectQr(text))

/** Pure rendering of a module matrix (rows of dark modules); null in, null out. Horizontal runs share one rectangle. */
internal fun deviceConnectQrMatrixToSvg(matrix: List<BooleanArray>?): String? {
    if (matrix == null || matrix.isEmpty()) return null
    val size = matrix.size + QR_QUIET_ZONE_MODULES * 2
    val path = StringBuilder()
    for (y in matrix.indices) {
        val row = matrix[y]
        var x = 0
        while (x < row.size) {
            if (!row[x]) {
                x++
                continue
            }
            var end = x
            while (end < row.size && row[end]) end++
            val run = end - x
            path.append('M').append(x + QR_QUIET_ZONE_MODULES).append(' ').append(y + QR_QUIET_ZONE_MODULES)
            path.append('h').append(run).append("v1h-").append(run).append('z')
            x = end
        }
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $size $size\" shape-rendering=\"crispEdges\">" +
        "<rect width=\"$size\" height=\"$size\" fill=\"#fff\"/><path d=\"$path\" fill=\"#000\"/></svg>"
}
