package com.metrolist.core.api.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoragePureTest {
    private val root: File = Files.createTempDirectory("mm-storage-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun dirSizeSumsNestedFiles() {
        File(root, "a").writeBytes(ByteArray(10))
        File(root, "sub/deeper").mkdirs()
        File(root, "sub/b").writeBytes(ByteArray(5))
        File(root, "sub/deeper/c").writeBytes(ByteArray(7))
        assertEquals(22L, DirSize.bytes(root))
    }

    @Test
    fun missingOrEmptyDirIsZero() {
        assertEquals(0L, DirSize.bytes(File(root, "none")))
        assertEquals(0L, DirSize.bytes(root))
    }

    @Test
    fun clearContentsKeepsTheFolder() {
        File(root, "a").writeBytes(ByteArray(10))
        File(root, "sub").mkdirs()
        File(root, "sub/b").writeBytes(ByteArray(5))
        assertTrue(DirSize.clearContents(root))
        assertTrue(root.isDirectory)
        assertEquals(0, root.listFiles()!!.size)
        assertEquals(0L, DirSize.bytes(root))
    }

    @Test
    fun clearingAMissingFolderIsFine() {
        assertTrue(DirSize.clearContents(File(root, "none")))
    }

    @Test
    fun cacheKindParsesOnlyTheFourCaches() {
        assertEquals(CacheKind.SONG, CacheKind.parse("songCache"))
        assertEquals(CacheKind.IMAGE, CacheKind.parse("imageCache"))
        assertEquals(CacheKind.VIDEO, CacheKind.parse("videoCache"))
        assertEquals(CacheKind.ANIMATED, CacheKind.parse("animatedArt"))
        assertNull(CacheKind.parse("downloads"))
        assertNull(CacheKind.parse(null))
    }

    @Test
    fun limitsAreBytesAndZeroMeansNone() {
        assertEquals(1024L * 1024, StorageLimits.toBytes(1))
        assertEquals(512L * 1024 * 1024, StorageLimits.toBytes(512))
        assertNull(StorageLimits.toBytes(0))
        assertNull(StorageLimits.toBytes(-1))
    }
}
