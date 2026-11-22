package com.metrolist.core.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.awt.Window

/**
 * Seams between the headless core and the Compose v1 app (desktopApp). Every hook has a headless default, so the sidecar
 * never has to set anything; desktopApp registers the real implementations from `DesktopCoreSeams.install()`.
 */

/** Image-cache hooks. desktopApp wires the Coil eviction and the Compose-side artwork preview holder. */
object ArtworkCache {
    @Volatile
    var evict: (String?) -> Unit = {}

    /** Called by PlayerState whenever the current track (id, artwork url) changes. */
    @Volatile
    var onTrackArtwork: (String?, String?) -> Unit = { _, _ -> }
}

/** Window facts that only exist in the v1 app. In the sidecar the window is never visible and showing it is a no-op. */
object WindowHost {
    /** True while the main window is visible (v1: the tray manager's state). */
    val visible = MutableStateFlow(false)

    @Volatile
    var showWindow: () -> Unit = {}

    /** Emits whether a Compose window is showing and not minimised (v1 playback UI visibility). */
    @Volatile
    var playbackVisibility: () -> Flow<Boolean> = { flowOf(false) }
}

/** Installed by the v1 app so the crash-recovery flow can attach its screenshot hint to the crash dialog. */
object CrashHooks {
    @Volatile
    var crashHintInstaller: (window: Window, onCopy: () -> Unit, onShare: () -> Unit) -> Unit = { _, _, _ -> }
}
