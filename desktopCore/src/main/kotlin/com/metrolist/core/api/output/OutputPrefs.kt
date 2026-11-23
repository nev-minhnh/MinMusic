package com.metrolist.core.api.output

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.core.CoreLog
import java.io.File

/**
 * The output device picked with `player.output.set`. `com.metrolist.core.prefs.CorePrefs` (key `outputDevice`) is not
 * on this branch yet (M1-2b), so the choice is kept in `<data root>/output-device.txt` (data root = the
 * `minmusic.dataRoot` property set by SidecarMain from `--data-root`); without a data root it lives in memory only.
 * [chosen] is Compose state so `player.state.output` re-emits when it changes.
 */
object OutputPrefs {
    var chosen: String? by mutableStateOf(null)
        private set

    private fun file(): File? = System.getProperty("minmusic.dataRoot")?.takeIf { it.isNotBlank() }?.let { File(it, "output-device.txt") }

    /** Reads the stored choice (if any) into [chosen] and returns it. */
    fun restore(): String? {
        val stored =
            runCatching { file()?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() } }
                .onFailure { CoreLog.error("output device: cannot read the stored choice", it) }
                .getOrNull()
        if (stored != null) chosen = stored
        return chosen
    }

    fun set(id: String) {
        chosen = id
        runCatching {
            file()?.let { f ->
                f.parentFile?.mkdirs()
                f.writeText(id, Charsets.UTF_8)
            }
        }.onFailure { CoreLog.error("output device: cannot store the choice", it) }
    }
}
