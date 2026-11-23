package com.metrolist.core.api.importfiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalIdTest {
    @Test
    fun isLocalPlusFirstSixteenHexOfSha1() {
        // SHA-1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        assertEquals("local_a9993e364706816a", LocalId.of("abc"))
    }

    @Test
    fun stableAndPathSpecific() {
        val a = LocalId.of("D:\\Music\\a.flac")
        assertEquals(a, LocalId.of("D:\\Music\\a.flac"))
        assertNotEquals(a, LocalId.of("D:\\Music\\b.flac"))
        assertEquals(6 + 16, a.length)
        assertTrue(a.drop(6).all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun recognisesItsOwnPrefix() {
        assertTrue(LocalId.isLocal(LocalId.of("x")))
        assertTrue(!LocalId.isLocal("dQw4w9WgXcQ"))
    }
}
