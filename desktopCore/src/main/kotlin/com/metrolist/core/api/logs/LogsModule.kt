package com.metrolist.core.api.logs

import com.metrolist.core.CoreConfig
import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.handler
import com.metrolist.core.server.long
import com.metrolist.core.server.str
import com.metrolist.desktop.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `app.logs.tail {file: app|shell|ui, lines: 1..2000}` -> `{text}` (api.md 3.1). It does not wait for the backend, so
 * the log can still be read when the backend failed to start. A missing file gives an empty text.
 */
object LogsModule : ApiModule {
    override val handlers: Map<String, Handler> =
        mapOf(
            "app.logs.tail" to
                handler { params, _ ->
                    val kind = params.str("file")?.takeIf { it in LogFiles.KINDS } ?: badRequest("file: app|shell|ui")
                    val lines = params.long("lines")?.takeIf { it in 1L..LogFiles.MAX_LINES.toLong() } ?: badRequest("lines: 1..${LogFiles.MAX_LINES}")
                    val text =
                        withContext(Dispatchers.IO) {
                            // Signature: AppLogger.flush() (no-op risk when the logger is not initialised yet)
                            runCatching { AppLogger.flush() }
                            LogFiles.resolve(kind, CoreConfig.logDirectory)?.let { TailReader.tail(it, lines.toInt()) }.orEmpty()
                        }
                    buildJsonObject { put("text", text) }
                },
        )
}
