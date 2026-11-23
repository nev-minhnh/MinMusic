package com.metrolist.core.api.backup

import com.metrolist.shared.backup.BackupArchiveLimits
import com.metrolist.shared.backup.BackupManifest
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestInspectTest {
    private val root: File = Files.createTempDirectory("manifest-inspect").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun zip(
        name: String,
        vararg entries: Triple<String, ByteArray, Long?>,
    ): File {
        val file = File(root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((entryName, bytes, time) in entries) {
                out.putNextEntry(ZipEntry(entryName).also { entry -> time?.let { entry.time = it } })
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun manifest(
        platform: String = "DESKTOP",
        formatVersion: Int = 2,
        databaseVersion: String = "69",
    ) = """{"formatVersion":$formatVersion,"platform":"$platform","databaseVersion":$databaseVersion,"extra":true}""".encodeToByteArray()

    @Test
    fun validDesktopBackupReportsPlatformVersionAndEntryTime() {
        val time = 1_763_000_000_000L
        val result = ManifestInspect.inspect(zip("ok.zip", Triple("backup.json", manifest(), time), Triple("song.db", ByteArray(8), null)), 69)
        assertTrue(result.ok, result.error)
        assertEquals("desktop", result.platform)
        assertEquals(69, result.dbVersion)
        assertNotNull(result.createdAt)
        // ZIP stores DOS time with a 2 s granularity.
        assertTrue(kotlin.math.abs(result.createdAt - time) <= 2_000L, "createdAt=${result.createdAt}")
        assertNull(result.error)
    }

    @Test
    fun androidBackupIsNotReportedAsDesktop() {
        val result = ManifestInspect.inspect(zip("android.zip", Triple("backup.json", manifest(platform = "ANDROID", databaseVersion = "62"), null)), 69)
        assertTrue(result.ok)
        assertEquals("android", result.platform)
        assertEquals(62, result.dbVersion)
    }

    @Test
    fun missingManifestFails() {
        val result = ManifestInspect.inspect(zip("nomanifest.zip", Triple("song.db", ByteArray(8), null)), 69)
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("backup.json"), result.error)
    }

    @Test
    fun oversizedManifestFails() {
        val padding = " ".repeat(BackupArchiveLimits.MANIFEST_BYTES.toInt())
        val big = manifest().decodeToString().dropLast(1) + padding + "}"
        val result = ManifestInspect.inspect(zip("big.zip", Triple("backup.json", big.encodeToByteArray(), null)), 69)
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("too large"), result.error)
    }

    @Test
    fun manifestExactlyAtTheLimitIsAccepted() {
        val base = manifest().decodeToString()
        val padded = base.dropLast(1) + " ".repeat(BackupArchiveLimits.MANIFEST_BYTES.toInt() - base.length) + "}"
        assertEquals(BackupArchiveLimits.MANIFEST_BYTES.toInt(), padded.length)
        assertTrue(ManifestInspect.inspect(zip("edge.zip", Triple("backup.json", padded.encodeToByteArray(), null)), 69).ok)
    }

    @Test
    fun unsupportedFormatAndNewerDatabaseFail() {
        assertFalse(ManifestInspect.inspect(zip("future.zip", Triple("backup.json", manifest(formatVersion = BackupManifest.CURRENT_FORMAT_VERSION + 1), null)), 69).ok)
        assertFalse(ManifestInspect.inspect(zip("newer.zip", Triple("backup.json", manifest(databaseVersion = "70"), null)), 69).ok)
    }

    @Test
    fun notAZipAndMissingFileFailWithoutThrowing() {
        val garbage = File(root, "garbage.zip").apply { writeText("this is not a zip") }
        assertFalse(ManifestInspect.inspect(garbage, 69).ok)
        assertFalse(ManifestInspect.inspect(File(root, "absent.zip"), 69).ok)
        assertFalse(ManifestInspect.inspect(File(root, "empty.zip").apply { writeBytes(ByteArray(0)) }, 69).ok)
    }

    @Test
    fun malformedManifestJsonFails() {
        val result = ManifestInspect.inspect(zip("bad.zip", Triple("backup.json", "{not json".encodeToByteArray(), null)), 69)
        assertFalse(result.ok)
        assertEquals("unknown", result.platform)
    }

    @Test
    fun manifestWithoutDatabaseVersionIsAcceptedAsVersionZero() {
        val json = """{"formatVersion":2,"platform":"ANDROID"}""".encodeToByteArray()
        val result = ManifestInspect.inspect(zip("noversion.zip", Triple("backup.json", json, null)), 69)
        assertTrue(result.ok, result.error)
        assertEquals(0, result.dbVersion)
    }
}
