package com.metrolist.core.api.library

import com.metrolist.core.prefs.CorePrefs
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorePrefsTest {
    private lateinit var dir: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("core-prefs-test").toFile()
        file = File(dir, "state/core-prefs.json")
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun missingFileReadsAsEmptyAndWritesNothing() {
        val prefs = CorePrefs(file)
        assertEquals(emptySet(), prefs.pinnedUserAlbums())
        assertFalse(file.exists())
    }

    @Test
    fun pinsSurviveANewInstance() {
        val prefs = CorePrefs(file)
        prefs.setPinned("lp:a", true)
        prefs.setPinned("lp:b", true)
        prefs.setPinned("lp:a", false)
        assertTrue(prefs.isPinned("lp:b"))
        assertFalse(prefs.isPinned("lp:a"))
        val reloaded = CorePrefs(file)
        assertEquals(setOf("lp:b"), reloaded.pinnedUserAlbums())
        assertTrue(file.readText().contains("pinnedUserAlbums"))
    }

    @Test
    fun theWriteLeavesNoTemporaryFileBehind() {
        val prefs = CorePrefs(file)
        repeat(5) { prefs.setPinned("lp:$it", true) }
        assertEquals(listOf("core-prefs.json"), file.parentFile.list().orEmpty().toList())
    }

    @Test
    fun anUnchangedSetIsNotRewritten() {
        val prefs = CorePrefs(file)
        prefs.setPinned("lp:a", true)
        val stamp = file.lastModified()
        file.setLastModified(stamp - 10_000)
        val before = file.lastModified()
        prefs.setPinned("lp:a", true)
        prefs.setPinned("lp:never", false)
        assertEquals(before, file.lastModified())
    }

    @Test
    fun aCorruptFileReadsAsEmptyAndIsReplacedOnTheNextWrite() {
        file.parentFile.mkdirs()
        file.writeText("{ not json")
        val prefs = CorePrefs(file)
        assertEquals(emptySet(), prefs.pinnedUserAlbums())
        prefs.setPinned("lp:a", true)
        assertEquals(setOf("lp:a"), CorePrefs(file).pinnedUserAlbums())
    }

    @Test
    fun retainDropsPinsOfDeletedAlbums() {
        val prefs = CorePrefs(file)
        prefs.setPinned("lp:a", true)
        prefs.setPinned("lp:b", true)
        prefs.retainPinned(setOf("lp:b", "lp:c"))
        assertEquals(setOf("lp:b"), CorePrefs(file).pinnedUserAlbums())
    }
}
