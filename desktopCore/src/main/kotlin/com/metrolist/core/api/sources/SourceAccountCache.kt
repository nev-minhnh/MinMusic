package com.metrolist.core.api.sources

import com.metrolist.core.api.account.AccountBus
import com.metrolist.desktop.source.DesktopSourceLogins
import com.metrolist.shared.source.SourceAccount
import com.metrolist.shared.source.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The last known Zing/NCT account (display name, VIP or FREE) per source. `sources.list` reads it and never waits for the
 * network: a stale entry is refreshed in the background (at most every 10 minutes per source) and announced with
 * `sources.changed`. After a login or logout the handler puts or clears the entry directly.
 */
object SourceAccountCache {
    class Entry(
        val account: SourceAccount?,
        val checkedAt: Long,
    )

    private val entries = ConcurrentHashMap<SourceId, Entry>()
    private val inflight = ConcurrentHashMap.newKeySet<SourceId>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(id: SourceId): Entry? = entries[id]

    fun put(
        id: SourceId,
        account: SourceAccount?,
    ) {
        entries[id] = Entry(account, System.currentTimeMillis())
    }

    fun clear(id: SourceId) {
        entries.remove(id)
    }

    fun refreshIfStale(id: SourceId) {
        val now = System.currentTimeMillis()
        if (!SourcesMapper.needsRefresh(entries[id]?.checkedAt, now)) return
        if (!inflight.add(id)) return
        scope.launch {
            try {
                // DesktopSourceLogins.account(source): Result<SourceAccount?> (success(null) = signed out, failure = unreachable)
                val result = DesktopSourceLogins.account(id)
                val before = entries[id]?.account
                // A failed check keeps the previous account but moves the timestamp, so an unreachable source is not hammered.
                val next = if (result.isSuccess) result.getOrNull() else before
                put(id, next)
                if (before != next) AccountBus.sourcesChanged()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                put(id, entries[id]?.account)
            } finally {
                inflight.remove(id)
            }
        }
    }
}
