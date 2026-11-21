package com.metrolist.desktop.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackErrorContext
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.youtube.playback.PlaybackResource

/**
 * MinMusic sends no crash or error reports. Playback failures are already recorded locally by
 * PlaybackEventLog and AppLogger, so these hooks intentionally do nothing.
 */
internal object PlaybackErrorReporter {
    @Suppress("UNUSED_PARAMETER")
    fun setActivePlayback(
        song: SongItem,
        resource: PlaybackResource?,
    ) = Unit

    fun clearActivePlayback() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun report(
        error: Throwable,
        context: PlaybackErrorContext,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun report(
        failure: PlaybackFailure,
        context: PlaybackErrorContext,
    ) = Unit
}
