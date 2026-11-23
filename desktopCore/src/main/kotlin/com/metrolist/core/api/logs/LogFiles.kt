package com.metrolist.core.api.logs

import java.io.File

/** Maps the `file` parameter of `app.logs.tail` to a file in the log directory. Pure apart from reading the directory. */
object LogFiles {
    const val MAX_LINES = 2000
    val KINDS = setOf("app", "shell", "ui")

    /** `app` -> the most recently written `app.log` or `app.log.N`; `shell` / `ui` -> `shell.log` / `ui.log`. Null when absent. */
    fun resolve(
        kind: String,
        logDirectory: File,
    ): File? =
        when (kind) {
            "app" ->
                logDirectory
                    .listFiles { file -> file.isFile && isAppLog(file.name) }
                    .orEmpty()
                    .maxByOrNull { it.lastModified() }
            "shell" -> File(logDirectory, "shell.log").takeIf(File::isFile)
            "ui" -> File(logDirectory, "ui.log").takeIf(File::isFile)
            else -> null
        }

    internal fun isAppLog(name: String): Boolean = name == "app.log" || Regex("app\\.log\\.\\d+").matches(name)
}
