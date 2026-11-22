package com.metrolist.core.api.queue

import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.sourceOf

/**
 * Whether "next" can do something. True when a later entry exists, when repeat-all would wrap, or when the core would
 * load a radio at the end of a YouTube queue (autoplay: `autoLoadMore`, repeat off, a YouTube track playing). Mirrors
 * `decidePlaybackQueueTransition` in the desktop app: next entry, then restart queue, then autoplay, else stop.
 */
fun canNext(
    currentIndex: Int,
    size: Int,
    repeat: RepeatMode,
    autoLoadMore: Boolean,
    trackId: String?,
): Boolean =
    currentIndex + 1 < size ||
        repeat == RepeatMode.ALL ||
        (autoLoadMore && repeat == RepeatMode.OFF && trackId != null && sourceOf(trackId) == SourceId.YT)
