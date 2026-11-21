package com.metrolist.shared.source

import com.metrolist.shared.settings.Settings
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

enum class SourceHealth {
    HEALTHY,
    DEGRADED,
}

class SourceUnavailableException(
    val source: SourceId,
    val reason: Reason,
) : Exception("Source ${source.key} is unavailable: ${reason.name.lowercase()}") {
    enum class Reason {
        NOT_REGISTERED,
        DISABLED,
        DEGRADED,
    }
}

/**
 * Holds the non-YouTube sources and acts as their kill switch: a source is used only while its
 * Settings toggle is on and it has not failed [FAILURE_THRESHOLD] times within [FAILURE_WINDOW_MS].
 * A success or flipping the toggle clears the failure history.
 */
class SourceRegistry(
    sources: List<MusicSource> = emptyList(),
    private val enabledSetting: (SourceId) -> Boolean = { Settings.shared.isSourceEnabled(it) },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val sources = sources.associateBy(MusicSource::id)
    private val lock = ReentrantLock()
    private val failures = mutableMapOf<SourceId, ArrayDeque<Long>>()
    private val observedEnabled = mutableMapOf<SourceId, Boolean>()

    fun isEnabled(id: SourceId): Boolean {
        if (id == SourceId.YT || id == SourceId.LOCAL) return true
        val enabled = enabledSetting(id)
        lock.withLock {
            // Flipping the toggle is the user's manual reset of a degraded source.
            if (observedEnabled.put(id, enabled).let { it != null && it != enabled }) failures.remove(id)
        }
        return enabled
    }

    fun health(id: SourceId): SourceHealth {
        isEnabled(id)
        return lock.withLock {
            val recent = failures[id]?.also { prune(it) }
            if ((recent?.size ?: 0) >= FAILURE_THRESHOLD) SourceHealth.DEGRADED else SourceHealth.HEALTHY
        }
    }

    /** The registered source when it is enabled and healthy, otherwise null. */
    fun available(id: SourceId): MusicSource? = sources[id]?.takeIf { isEnabled(id) && health(id) == SourceHealth.HEALTHY }

    suspend fun <T> call(
        id: SourceId,
        block: suspend (MusicSource) -> T,
    ): T {
        val source = sources[id] ?: throw SourceUnavailableException(id, SourceUnavailableException.Reason.NOT_REGISTERED)
        if (!isEnabled(id)) throw SourceUnavailableException(id, SourceUnavailableException.Reason.DISABLED)
        if (health(id) == SourceHealth.DEGRADED) throw SourceUnavailableException(id, SourceUnavailableException.Reason.DEGRADED)
        return try {
            block(source).also { reset(id) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordFailure(id)
            throw error
        }
    }

    fun reset(id: SourceId) {
        lock.withLock { failures.remove(id) }
    }

    private fun recordFailure(id: SourceId) {
        lock.withLock {
            val recent = failures.getOrPut(id) { ArrayDeque() }
            recent.addLast(nowMs())
            prune(recent)
        }
    }

    private fun prune(recent: ArrayDeque<Long>) {
        val cutoff = nowMs() - FAILURE_WINDOW_MS
        while (recent.isNotEmpty() && recent.first() <= cutoff) recent.removeFirst()
    }

    companion object {
        const val FAILURE_THRESHOLD = 5
        const val FAILURE_WINDOW_MS = 10 * 60 * 1000L
    }
}
