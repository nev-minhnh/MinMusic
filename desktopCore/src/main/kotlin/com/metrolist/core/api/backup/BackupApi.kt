package com.metrolist.core.api.backup

import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.desktop.backup.DesktopBackupRestoreManager
import com.metrolist.shared.backup.UnsupportedForeignLibraryException
import com.metrolist.shared.backup.importForeignBackup
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.util.zip.ZipException

/**
 * api.md 3.15: `backup.create`, `backup.inspect`, `backup.restore`, `import.foreignBackup`.
 * `backup.restore` only stages the restore; the UI then calls the shell `app_restart` and the next core start applies it
 * (CoreBackend.init calls DesktopBackupRestoreManager.applyPendingRestore before the database opens).
 */
object BackupApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "backup.create" to handler { params, _ -> create(pathParam(params)) },
            "backup.inspect" to handler { params, _ -> inspect(pathParam(params)).toJson() },
            "backup.restore" to handler { params, _ -> restore(pathParam(params)) },
            "import.foreignBackup" to handler { params, _ -> importForeign(pathParam(params)) },
        )

    /** A required, non-blank, absolute file path. */
    private fun pathParam(params: JsonObject): File {
        val path = params.requireStr("path")
        if (path.isBlank()) badRequest("path is empty")
        val file = File(path)
        if (!file.isAbsolute) badRequest("path must be absolute")
        return file
    }

    private suspend fun create(file: File): JsonElement {
        backendReady()
        // Signature: DesktopBackupRestoreManager.backup(destination: File): Result<Unit>
        val result = withContext(Dispatchers.IO) { DesktopBackupRestoreManager.backup(file) }
        result.exceptionOrNull()?.let { throw ApiException("internal", it.message ?: "backup failed") }
        return buildJsonObject { put("bytes", file.length()) }
    }

    private fun inspect(file: File): InspectResult = ManifestInspect.inspect(file)

    private suspend fun restore(file: File): JsonElement {
        backendReady()
        // Signature: DesktopBackupRestoreManager.stageRestore(source: File): Result<Unit>
        val result = withContext(Dispatchers.IO) { DesktopBackupRestoreManager.stageRestore(file) }
        result.exceptionOrNull()?.let { throw ApiException(errorCodeFor(it), it.message ?: "restore failed") }
        return buildJsonObject { put("staged", true) }
    }

    private suspend fun importForeign(file: File): JsonElement {
        if (!file.isFile) badRequest("file not found")
        backendReady()
        // Signature: suspend fun MusicDatabase.importForeignBackup(input: InputStream, stagingDirectory: File, driver: SQLiteDriver): ForeignImportSummary
        val summary =
            try {
                val database = GlobalContext.get().get<MusicDatabase>()
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        database.importForeignBackup(input, File(XdgUtils.getXdgCacheHome(), "metrolist/foreign-import"), JdbcSQLiteDriver())
                    }
                }
            } catch (error: UnsupportedForeignLibraryException) {
                throw ApiException("bad_request", error.message ?: "Not a supported library backup")
            } catch (error: ZipException) {
                throw ApiException("bad_request", error.message ?: "Not a supported library backup")
            }
        return buildJsonObject { put("imported", summary.songs + summary.playlists) }
    }

    private fun InspectResult.toJson(): JsonObject =
        buildJsonObject {
            put("ok", ok)
            put("platform", platform)
            put("dbVersion", dbVersion)
            if (createdAt != null) put("createdAt", createdAt)
            if (error != null) put("error", error)
        }

    /** Validation failures of an archive are the caller's fault (`bad_request`); anything else (disk, SQLite) is `internal`. */
    internal fun errorCodeFor(error: Throwable): String =
        when (error) {
            is IllegalArgumentException, is IllegalStateException, is ZipException, is FileNotFoundException, is NoSuchFileException -> "bad_request"
            else -> "internal"
        }
}
