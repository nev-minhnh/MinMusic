package com.metrolist.core.api.sources

import com.metrolist.shared.utils.XdgUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A per-track source choice (`track.setSource`): play [source]'s song [id] instead of the original. */
@Serializable
data class TrackOverride(
    val source: String,
    val id: String,
)

@Serializable
data class SourcePrefsData(
    val priority: List<String> = SourcePrefsStore.DEFAULT_PRIORITY,
    val autoFallback: Boolean = true,
    val trackOverrides: Map<String, TrackOverride> = emptyMap(),
)

/**
 * The source choices v1 has no setting for (priority, auto fallback, per-track overrides), kept in one small JSON file with
 * an atomic write. It holds no secrets. Priority and auto fallback are stored only: v1 has no routing hook for them.
 */
class SourcePrefsStore(
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: SourcePrefsData? = null

    @Synchronized
    fun read(): SourcePrefsData = cached ?: load().also { cached = it }

    @Synchronized
    fun update(change: (SourcePrefsData) -> SourcePrefsData): SourcePrefsData {
        val next = change(read())
        write(next)
        cached = next
        return next
    }

    private fun load(): SourcePrefsData {
        val data = runCatching { json.decodeFromString<SourcePrefsData>(file.readText()) }.getOrNull() ?: return SourcePrefsData()
        // A damaged or hand-edited priority falls back to the default instead of breaking sources.list.
        return if (SourcesMapper.isValidPriority(data.priority)) data else data.copy(priority = DEFAULT_PRIORITY)
    }

    private fun write(data: SourcePrefsData) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(SourcePrefsData.serializer(), data))
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        val DEFAULT_PRIORITY = listOf("yt", "zing", "nct", "sc")
    }
}

/** The live store: `<XdgUtils.getXdgStateHome()>/metrolist/source-prefs.json`. */
object SourcePrefs {
    // XdgUtils.getXdgStateHome(): File (shared/src/desktopMain/.../utils/XdgUtils.kt:57)
    private val store by lazy { SourcePrefsStore(XdgUtils.getXdgStateHome().resolve("metrolist").resolve("source-prefs.json")) }

    var priority: List<String>
        get() = store.read().priority
        set(value) {
            store.update { it.copy(priority = value) }
        }

    var autoFallback: Boolean
        get() = store.read().autoFallback
        set(value) {
            store.update { it.copy(autoFallback = value) }
        }

    val trackOverrides: Map<String, TrackOverride> get() = store.read().trackOverrides

    fun setOverride(
        trackId: String,
        override: TrackOverride?,
    ) {
        store.update { data ->
            data.copy(trackOverrides = if (override == null) data.trackOverrides - trackId else data.trackOverrides + (trackId to override))
        }
    }
}
