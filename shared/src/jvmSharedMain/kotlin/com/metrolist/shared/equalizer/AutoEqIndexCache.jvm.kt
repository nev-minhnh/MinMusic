/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import java.io.File

private fun indexFile(): File? = autoEqCacheDirectory()?.let { File(it, "INDEX.md") }

internal actual fun readAutoEqIndexCache(): Pair<String, Long>? {
    val file = indexFile()?.takeIf { it.isFile } ?: return null
    return file.readText() to file.lastModified()
}

internal actual fun writeAutoEqIndexCache(content: String) {
    val file = indexFile() ?: return
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(content)
    if (!temporary.renameTo(file)) {
        file.delete()
        check(temporary.renameTo(file))
    }
}
