package com.metrolist.core.api.backup

import com.metrolist.shared.backup.BACKUP_MANIFEST_ENTRY
import com.metrolist.shared.backup.BackupArchiveLimits
import com.metrolist.shared.backup.BackupManifest
import com.metrolist.shared.backup.requireSupportedBackupDatabaseVersion
import com.metrolist.shared.backup.requireSupportedFormat
import com.metrolist.shared.db.DATABASE_VERSION
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Result of `backup.inspect` (api.md 3.15). `platform` is the lower-case BackupPlatform name. */
data class InspectResult(
    val ok: Boolean,
    val platform: String,
    val dbVersion: Int,
    val createdAt: Long?,
    val error: String?,
)

/**
 * Reads only the `backup.json` entry of a backup archive, like the private `preflightManifest` of
 * DesktopBackupRestoreManager: at most BackupArchiveLimits.ENTRY_COUNT entries, one manifest, capped at
 * BackupArchiveLimits.MANIFEST_BYTES, supported format and database version. Never throws.
 */
object ManifestInspect {
    private val json = Json { ignoreUnknownKeys = true }

    fun inspect(
        file: File,
        currentDatabaseVersion: Int = DATABASE_VERSION,
    ): InspectResult {
        if (!file.isFile) return failure("File not found")
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size > BackupArchiveLimits.ENTRY_COUNT) return failure("Backup contains too many entries")
                val manifests = entries.filter { it.name == BACKUP_MANIFEST_ENTRY }
                if (manifests.isEmpty()) return failure("Not a MinMusic backup (backup.json is missing)")
                if (manifests.size > 1) return failure("Backup contains duplicate manifests")
                val entry = manifests.single()
                val bytes =
                    zip.getInputStream(entry).use { it.readCapped(BackupArchiveLimits.MANIFEST_BYTES) }
                        ?: return failure("backup.json is too large")
                val manifest = json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
                manifest.requireSupportedFormat()
                manifest.databaseVersion?.let { requireSupportedBackupDatabaseVersion(it, currentDatabaseVersion) }
                InspectResult(
                    ok = true,
                    platform = manifest.platform.name.lowercase(),
                    dbVersion = manifest.databaseVersion ?: 0,
                    createdAt = entry.time.takeIf { it > 0 },
                    error = null,
                )
            }
        } catch (error: Exception) {
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun failure(message: String) = InspectResult(ok = false, platform = "unknown", dbVersion = 0, createdAt = null, error = message)

    /** Null when the stream holds more than [limit] bytes. */
    private fun InputStream.readCapped(limit: Long): ByteArray? {
        val bytes = readNBytes(limit.toInt() + 1)
        return if (bytes.size > limit) null else bytes
    }
}
