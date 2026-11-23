package com.metrolist.core.api.settings

import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Changed entries between two snapshots (keys of [after] whose value differs from [before]). Pure. */
fun diffSettings(
    before: Map<String, JsonElement>,
    after: Map<String, JsonElement>,
): Map<String, JsonElement> = after.filter { (key, value) -> before[key] != value }

/**
 * The shared view of the settings for the `settings` topic.
 *
 * Delta design: the registry has about 400 keys and Compose state is not observable from here for all of them, so every
 * write made through this API ([write]) re-reads all values at once and publishes the difference, and a 1 s poll
 * ([scan], run by each open subscription) catches writes made elsewhere in the core (for example by the player code).
 * Reading ~400 in-memory preference values takes well under a millisecond, so there is no per-key `snapshotFlow`.
 * A [write] holds the lock for its whole batch, so one `settings.set` yields exactly one `settings.changed`.
 */
object SettingsHub {
    private val lock = Any()
    private var last: Map<String, JsonElement>? = null
    private val flow = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** `{changed}` payload maps, one per published difference. */
    val changes: SharedFlow<JsonObject> get() = flow

    /** All values as clients see them (secrets as `{set}`). */
    fun read(settings: Settings = Settings.shared): Map<String, JsonElement> =
        settings.registry.associate { it.key to SettingJson.wire(it.key, settings.getRaw(it.key)) }

    /** Latest snapshot, refreshed first. */
    fun current(): Map<String, JsonElement> =
        synchronized(lock) {
            scanLocked()
            last ?: emptyMap()
        }

    /** Publishes what changed since the last scan. The first scan only records the baseline. */
    fun scan() = synchronized(lock) { scanLocked() }

    /** Runs [block] (the writes) and publishes the resulting difference as one event. */
    fun <T> write(block: () -> T): T =
        synchronized(lock) {
            if (last == null) last = read()
            try {
                block()
            } finally {
                scanLocked()
            }
        }

    private fun scanLocked() {
        val now = read()
        val before = last
        last = now
        if (before == null) return
        val changed = diffSettings(before, now)
        if (changed.isNotEmpty()) flow.tryEmit(JsonObject(changed))
    }
}
