package com.metrolist.core.api.queue

import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreLog
import com.metrolist.desktop.state.playback.PlayerState as DesktopPlayerState
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Bumps its number on every distinct queue state it is shown (a new entry list or a new current index). */
class QueueRevisionCounter {
    private var lastSeen: MusicQueueState? = null
    private var revision = 0L

    @Synchronized
    fun observe(state: MusicQueueState): Long {
        val previous = lastSeen
        if (state !== previous) {
            if (state != previous) revision++
            lastSeen = state
        }
        return revision
    }
}

/**
 * Queue revision of the running player. Readers call [observe] with the state they are about to send, so the number
 * never lags behind a state the UI has seen; [QueueTracker] also feeds it so revisions advance without any reader.
 */
object QueueRevision {
    private val counter = QueueRevisionCounter()

    fun observe(state: MusicQueueState): Long = counter.observe(state)
}

/**
 * Lazily started collector (first `player` or `queue` subscription): keeps [QueueRevision] current and logs
 * `queue end: autoplay=<on> seed=<id>` when playback reaches the last queue entry, which is where "only one song
 * plays" reports have to be diagnosed (autoplay off, or a source that cannot autoplay).
 */
object QueueTracker {
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        CoreBackend.scope.launch {
            try {
                CoreBackend.await()
                val flow = withContext(Dispatchers.Main) { DesktopPlayerState.queue.state }
                var lastEnd: Pair<Long, Int>? = null
                flow.collect { state ->
                    QueueRevision.observe(state)
                    if (QueueMapper.atEnd(state)) {
                        val last = state.entries.last()
                        val marker = last.key to state.entries.size
                        if (marker != lastEnd) {
                            lastEnd = marker
                            CoreLog.info("queue end: autoplay=${Settings.shared.autoLoadMore} seed=${last.song.id}")
                        }
                    } else {
                        lastEnd = null
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                started.set(false)
                CoreLog.error("queue tracker stopped", error)
            }
        }
    }
}
