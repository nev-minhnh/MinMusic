package com.metrolist.core.api.dc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortRangeTest {
    @Test
    fun edgesOfTheRange() {
        assertNull(DcPortRange.parse(1023.0))
        assertEquals(1024, DcPortRange.parse(1024.0))
        assertEquals(65535, DcPortRange.parse(65535.0))
        assertNull(DcPortRange.parse(65536.0))
        assertNull(DcPortRange.parse(0.0), "0 is allowed by the manager, not by the API")
    }

    @Test
    fun rejectsNonIntegersAndGarbage() {
        assertNull(DcPortRange.parse(null))
        assertNull(DcPortRange.parse(4242.5))
        assertNull(DcPortRange.parse(70000.0))
        assertNull(DcPortRange.parse(80.0))
        assertNull(DcPortRange.parse(Double.NaN))
        assertNull(DcPortRange.parse(Double.POSITIVE_INFINITY))
        assertNull(DcPortRange.parse(-1.0))
    }
}
