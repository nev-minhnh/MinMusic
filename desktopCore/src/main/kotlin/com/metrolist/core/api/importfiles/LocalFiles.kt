package com.metrolist.core.api.importfiles

import com.metrolist.shared.utils.XdgUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `local_<hash>` id -> absolute path of the imported file, kept in `<state dir>/metrolist/local-files.json` (the `song`
 * table has no path column). Writes are atomic (temp file + move). The player reads it through `LocalMediaPaths`.
 */
object LocalFiles {
    private val file: File by lazy { File(XdgUtils.getXdgStateHome().resolve("metrolist"), "local-files.json") }
    private var paths: MutableMap<String, String>? = null

    @Synchronized
    fun pathOf(id: String): String? = if (LocalId.isLocal(id)) load()[id] else null

    @Synchronized
    fun putAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val current = load()
        if (entries.all { current[it.key] == it.value }) return
        current.putAll(entries)
        write(current)
    }

    private fun load(): MutableMap<String, String> {
        paths?.let { return it }
        val loaded =
            runCatching {
                if (!file.isFile) {
                    LinkedHashMap()
                } else {
                    Json
                        .parseToJsonElement(file.readText(Charsets.UTF_8))
                        .jsonObject
                        .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.let { k to it } }
                        .toMap(LinkedHashMap())
                }
            }.getOrElse { LinkedHashMap() }
        paths = loaded
        return loaded
    }

    private fun write(all: Map<String, String>) {
        val directory = file.absoluteFile.parentFile
        directory.mkdirs()
        val temp = File.createTempFile("local-files", ".tmp", directory)
        try {
            temp.writeText(JsonObject(all.mapValues { JsonPrimitive(it.value) }).toString(), Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }
}
