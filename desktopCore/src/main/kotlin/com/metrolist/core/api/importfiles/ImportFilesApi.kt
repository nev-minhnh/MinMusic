package com.metrolist.core.api.importfiles

import com.metrolist.core.server.Handler
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.handler
import com.metrolist.core.server.unsupported
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * api.md 3.4 `import.files {paths}` -> `{imported, failed}`.
 *
 * BLOCKED (reported in STATUS): there is no way to make a `local_<hash>` song playable without editing PlayerState or
 * SongItemMapping. v1 resolves a local file only from `SongItem.endpointParams`, and `toSongItem()` fills that only for
 * ids that are themselves `file:` URIs (SongItemMapping.kt:35); `localPlaybackResource` (PlayerState.kt:193) reads nothing
 * else, and the `song` table has no path column. Importing rows that cannot play would be worse than `unsupported`, so the
 * method validates its params and answers `unsupported` until a path hook exists. [LocalId] is ready for it.
 */
object ImportFilesApi {
    val handlers: Map<String, Handler> = mapOf("import.files" to handler { p, _ -> importFiles(p) })

    private fun importFiles(params: JsonObject): JsonElement {
        val paths = params["paths"] as? JsonArray ?: badRequest("missing 'paths'")
        paths.forEach { if ((it as? JsonPrimitive)?.isString != true) badRequest("'paths' must hold strings") }
        unsupported("importing local files needs a path hook in the player (see ImportFilesApi)")
    }
}
