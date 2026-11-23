package com.metrolist.core

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortablePathTest {
    private val command = "D:/Apps/MinMusic/sidecar/runtime/bin/minmusic-core.exe"

    @Test
    fun portableFlagResolvesDataNextToTheSidecarFolder() {
        val expected = Paths.get("D:/Apps/MinMusic/data").normalize().toString()
        assertEquals(expected, PortableMode.dataRoot("1", null, command))
        assertEquals(expected, PortableMode.dataRoot(" 1 ", "", command))
    }

    @Test
    fun otherFlagValuesAreNotPortable() {
        assertNull(PortableMode.dataRoot(null, null, command))
        assertNull(PortableMode.dataRoot("0", null, command))
        assertNull(PortableMode.dataRoot("true", null, command))
    }

    @Test
    fun anExistingRootWins() {
        assertNull(PortableMode.dataRoot("1", "C:/tests/root", command))
    }

    @Test
    fun unusableExecutablePathGivesNoRoot() {
        assertNull(PortableMode.dataRoot("1", null, null))
        assertNull(PortableMode.dataRoot("1", null, ""))
        assertNull(PortableMode.dataRoot("1", null, "minmusic-core.exe"))
        assertNull(PortableMode.dataRoot("1", null, "runtime/bin/minmusic-core.exe"))
    }
}
