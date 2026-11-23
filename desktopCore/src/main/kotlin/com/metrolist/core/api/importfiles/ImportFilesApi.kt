package com.metrolist.core.api.importfiles

import com.metrolist.core.CoreLog
import com.metrolist.core.api.library.LibraryChanges
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.handler
import com.metrolist.desktop.state.playback.LocalMediaPaths
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.time.Clock

/**
 * api.md 3.4 `import.files {paths}` -> `{imported, failed}`. Each readable audio file becomes a library song with the id
 * `local_<sha1(path)>`, its tags read by [LocalProbe]; the id -> path map lives in [LocalFiles] and the player finds the
 * file through the `LocalMediaPaths` hook installed here. A path that is missing, not a file or has no audio stream counts
 * as failed.
 */
object ImportFilesApi {
    init {
        LocalMediaPaths.resolver = LocalFiles::pathOf
    }

    val handlers: Map<String, Handler> = mapOf("import.files" to handler { p, _ -> importFiles(p) })

    private suspend fun importFiles(params: JsonObject): JsonElement {
        val raw = params["paths"] as? JsonArray ?: badRequest("missing 'paths'")
        val paths = raw.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: badRequest("'paths' must hold strings") }
        if (paths.isEmpty()) return result(0, 0)
        backendReady()
        return withContext(Dispatchers.IO) {
            val dao = GlobalContext.get().get<DatabaseDao>()
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val added = LinkedHashMap<String, String>()
            var failed = 0
            for (path in paths) {
                val file = runCatching { File(path).absoluteFile.canonicalFile }.getOrNull()
                val tags = file?.takeIf(File::isFile)?.let { runCatching { LocalProbe.probe(it.path) }.getOrNull() }
                if (file == null || tags == null) {
                    failed++
                    continue
                }
                val id = LocalId.of(file.path)
                // SongDao.upsert(song: SongEntity) (@Transaction: insert, else update)
                dao.upsert(
                    SongEntity(
                        id = id,
                        title = tags.title ?: file.nameWithoutExtension,
                        customArtist = tags.artist,
                        duration = tags.durationSec,
                        albumName = tags.album,
                        year = tags.year,
                        inLibrary = now,
                        isLocal = true,
                    ),
                )
                added[id] = file.path
            }
            LocalFiles.putAll(added)
            if (added.isNotEmpty()) LibraryChanges.bump(listOf("songs"))
            CoreLog.info("import.files: imported=${added.size} failed=$failed")
            result(added.size, failed)
        }
    }

    private fun result(
        imported: Int,
        failed: Int,
    ): JsonElement =
        buildJsonObject {
            put("imported", imported)
            put("failed", failed)
        }
}
