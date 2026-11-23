package com.metrolist.core.api.queue

import com.metrolist.shared.playback.MusicQueueState

/** Pure index arithmetic for `queue.remove`, `queue.select`, `queue.clear`, `queue.move` and `queue.radio`. */
object QueueIndex {
    /** Index of the entry whose `qid` (its queue key as text) is [qid], or -1. */
    fun indexOfQid(
        state: MusicQueueState,
        qid: String,
    ): Int = state.entries.indexOfFirst { it.key.toString() == qid }

    /** Indices of the known [qids]; unknown ones are ignored (removing them is a no-op). */
    fun indicesOf(
        state: MusicQueueState,
        qids: Collection<String>,
    ): Set<Int> = qids.mapNotNullTo(LinkedHashSet()) { qid -> indexOfQid(state, qid).takeIf { it >= 0 } }

    /**
     * What `queue.clear` removes: everything except the playing entry when [keepCurrent] (the playing entry stays as
     * the only one), otherwise everything.
     */
    fun clearIndices(
        size: Int,
        currentIndex: Int,
        keepCurrent: Boolean,
    ): Set<Int> = (0 until size).filterTo(LinkedHashSet()) { !keepCurrent || it != currentIndex }

    /** True when [from] and [to] are valid slots of a queue of [size] entries. */
    fun validMove(
        size: Int,
        from: Int,
        to: Int,
    ): Boolean = from in 0 until size && to in 0 until size

    /** `queue.radio` is done when the queue holds at least [minSize] entries and the seed is the playing one. */
    fun radioReady(
        state: MusicQueueState,
        seedId: String,
        minSize: Int,
    ): Boolean = state.entries.size >= minSize && state.entries.getOrNull(state.currentIndex)?.song?.id == seedId

    /**
     * Where `queue.add` with a numeric position puts the first song: that slot, never past the old end. Unlike
     * [QueueMapper.insertIndex] it may be before the playing entry (the playing index then follows the insertion).
     */
    fun addSlot(
        requested: Int,
        sizeBefore: Int,
    ): Int = requested.coerceIn(0, sizeBefore)
}
