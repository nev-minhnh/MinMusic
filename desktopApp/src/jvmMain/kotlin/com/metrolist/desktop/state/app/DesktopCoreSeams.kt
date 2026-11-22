/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.snapshotFlow
import com.metrolist.core.bridge.ArtworkCache
import com.metrolist.core.bridge.CoreNotifier
import com.metrolist.core.bridge.CrashHooks
import com.metrolist.core.bridge.WindowHost
import com.metrolist.desktop.crash.installCrashScreenshotHint
import com.metrolist.desktop.state.playback.PlayerArtworkPreviews
import com.metrolist.desktop.utils.ComposeTrayManager
import com.metrolist.desktop.utils.DesktopImageLoader
import com.metrolist.desktop.window.playbackWindowVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The old `AppState.snackbarHostState`: AppState itself now lives in desktopCore and has no Compose types. */
val AppState.snackbarHostState: SnackbarHostState get() = DesktopCoreSeams.snackbarHostState

/**
 * Registers the Compose side of every desktopCore seam (see desktopCore/docs/m1-1-checklist.md). `Main` calls [install]
 * before anything touches PlayerState or AppState.
 */
object DesktopCoreSeams {
    val snackbarHostState = SnackbarHostState()

    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        ArtworkCache.evict = { url -> DesktopImageLoader.evictArtwork(url) }
        ArtworkCache.onTrackArtwork = { songId, url ->
            PlayerArtworkPreviews.current = PlayerArtworkPreviews.current.forTrack(songId, url)
        }
        WindowHost.showWindow = { ComposeTrayManager.showWindow() }
        WindowHost.playbackVisibility = { playbackWindowVisibility() }
        CrashHooks.crashHintInstaller = { window, onCopy, onShare -> installCrashScreenshotHint(window, onCopy, onShare) }
        CoreNotifier.showWithAction = { message, actionLabel ->
            snackbarHostState.showSnackbar(message = message, actionLabel = actionLabel) == SnackbarResult.ActionPerformed
        }
        // Own scope on Dispatchers.Main (Swing): this must not touch AppState, whose init reads settings that a pending
        // backup restore may still replace.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            // Same queueing the snackbar always had: showSnackbar serialises on its own mutex.
            CoreNotifier.messages.collect { message -> launch { snackbarHostState.showSnackbar(message.text) } }
        }
        scope.launch {
            snapshotFlow { ComposeTrayManager.windowVisible }.collect { WindowHost.visible.value = it }
        }
    }
}
