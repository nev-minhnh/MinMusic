package com.metrolist.shared.deviceconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrSvgTest {
    private val link = "metrolist://device-connect?a=192.168.1.23:48765,10.0.0.5:48765&n=MinMusic-PC"

    @Test
    fun svgHasQuietZoneAndViewBoxInModules() {
        val matrix = assertNotNull(encodeDeviceConnectQr(link))
        val svg = assertNotNull(deviceConnectQrSvg(link))
        val size = matrix.size + 8
        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.contains("viewBox=\"0 0 $size $size\""), svg.take(120))
        assertTrue(svg.contains("shape-rendering=\"crispEdges\""))
        // Every module starts at least 4 modules from the edge: the quiet zone stays empty.
        val moves = Regex("M(\\d+) (\\d+)").findAll(svg).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { (x, y) -> x >= 4 && y >= 4 && y < size - 4 })
        assertTrue(!svg.contains("<script"))
    }

    @Test
    fun sameTextGivesSameSvg() {
        assertEquals(deviceConnectQrSvg(link), deviceConnectQrSvg(link))
        assertNotEquals(deviceConnectQrSvg(link), deviceConnectQrSvg("$link-2"))
    }

    @Test
    fun darkModulesMatchTheMatrixExactly() {
        val matrix = assertNotNull(encodeDeviceConnectQr(link))
        val svg = assertNotNull(deviceConnectQrSvg(link))
        val dark = HashSet<Pair<Int, Int>>()
        Regex("M(\\d+) (\\d+)h(\\d+)v1h-\\3z").findAll(svg).forEach {
            val x = it.groupValues[1].toInt() - 4
            val y = it.groupValues[2].toInt() - 4
            repeat(it.groupValues[3].toInt()) { dx -> dark += (x + dx) to y }
        }
        val expected = HashSet<Pair<Int, Int>>()
        matrix.forEachIndexed { y, row -> row.forEachIndexed { x, on -> if (on) expected += x to y } }
        assertEquals(expected, dark)
    }

    @Test
    fun nullMatrixGivesNull() {
        assertNull(deviceConnectQrMatrixToSvg(null))
        assertNull(deviceConnectQrSvg("x".repeat(5000)))
    }

    @Test
    fun linkWrapperMatchesTheInternalBuilder() {
        assertEquals(buildDeviceConnectLink(listOf("192.168.1.23"), 48765, "MinMusic-PC"), deviceConnectLinkFor(listOf("192.168.1.23"), 48765, "MinMusic-PC"))
        assertNull(deviceConnectLinkFor(emptyList(), 48765, "MinMusic-PC"))
    }
}
