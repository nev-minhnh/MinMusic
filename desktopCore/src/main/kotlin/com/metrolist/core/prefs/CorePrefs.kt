package com.metrolist.core.prefs

import com.metrolist.shared.utils.XdgUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The core's own small preferences, a JSON file next to the other state (`<state dir>/core-prefs.json`). Today it holds
 * `pinnedUserAlbums: string[]` (the `lp:<id>` of the pinned user albums). Writes are atomic: the new content goes to a
 * temporary file in the same directory and replaces the old file in one move, so a crash leaves either version whole.
 * A missing or unreadable file reads as empty. Backup inclusion is handled by M4-3.
 */
class CorePrefs(
    private val file: File,
) {
    private var pinned: Set<String>? = null

    @Synchronized
    fun pinnedUserAlbums(): Set<String> = load()

    @Synchronized
    fun isPinned(id: String): Boolean = id in load()

    /** Pins or unpins [id]; writes the file only when the set changes. */
    @Synchronized
    fun setPinned(
        id: String,
        pinned: Boolean,
    ) {
        val current = load()
        val next = if (pinned) current + id else current - id
        if (next == current) return
        this.pinned = next
        write(next)
    }

    /** Forgets the pins of deleted albums. */
    @Synchronized
    fun retainPinned(existing: Set<String>) {
        val current = load()
        val next = current.filterTo(LinkedHashSet()) { it in existing }
        if (next == current) return
        pinned = next
        write(next)
    }

    private fun load(): Set<String> {
        pinned?.let { return it }
        val loaded =
            try {
                if (!file.isFile) {
                    emptySet()
                } else {
                    val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
                    (root["pinnedUserAlbums"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                        ?.toCollection(LinkedHashSet())
                        ?: emptySet()
                }
            } catch (_: Exception) {
                emptySet()
            }
        pinned = loaded
        return loaded
    }

    private fun write(pins: Set<String>) {
        val json: JsonObject = buildJsonObject { put("pinnedUserAlbums", JsonArray(pins.map { JsonPrimitive(it) })) }
        val directory = file.absoluteFile.parentFile
        directory.mkdirs()
        val temp = File.createTempFile("core-prefs", ".tmp", directory)
        try {
            Files.write(temp.toPath(), json.toString().toByteArray(Charsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        /** `<state dir>/core-prefs.json`, the state dir being the one `app.info` reports. */
        val shared: CorePrefs by lazy { CorePrefs(File(XdgUtils.getXdgStateHome().resolve("metrolist"), "core-prefs.json")) }
    }
}
