package com.metrolist.core.api.download

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderValidateTest {
    private val root: File = Files.createTempDirectory("mm-folder-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun existingWritableFolderIsOk() {
        assertEquals(FolderCheck.OK, FolderValidate.check(root))
        assertNull(FolderCheck.OK.wire)
        assertTrue(FolderValidate.isOk(root))
    }

    @Test
    fun theProbeLeavesNothingBehind() {
        FolderValidate.check(root)
        assertEquals(emptyList(), root.listFiles()!!.toList())
    }

    @Test
    fun missingFolderIsMissing() {
        val gone = File(root, "nope/deeper")
        assertEquals(FolderCheck.MISSING, FolderValidate.check(gone))
        assertEquals("missing", FolderCheck.MISSING.wire)
        assertFalse(FolderValidate.isOk(gone))
    }

    @Test
    fun aFileIsNotAFolder() {
        val f = File(root, "a.txt").also { it.writeText("x") }
        assertEquals(FolderCheck.MISSING, FolderValidate.check(f))
        assertFalse(FolderValidate.isOk(f))
    }

    @Test
    fun wireNamesMatchTheApi() {
        assertEquals("not_writable", FolderCheck.NOT_WRITABLE.wire)
    }
}
