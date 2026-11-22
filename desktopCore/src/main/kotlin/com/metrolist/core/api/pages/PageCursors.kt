package com.metrolist.core.api.pages

import com.metrolist.core.server.ApiException
import java.security.SecureRandom

/**
 * Server-side store for paging state that cannot travel in a cursor string (a `PlaylistPage`, a `PodcastPage`).
 * LRU with [capacity] entries and a [ttlMs] lifetime counted from `put`; an unknown or expired id is a retryable
 * `bad_request`. The clock is injectable for tests.
 */
class PageCursorStore(
    private val capacity: Int = 64,
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(
        val value: Any,
        val expiresAt: Long,
    )

    private val random = SecureRandom()
    private val map =
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > capacity
        }

    fun put(value: Any): String =
        synchronized(map) {
            val now = clock()
            map.entries.removeIf { it.value.expiresAt <= now }
            var id: String
            do {
                id = newId()
            } while (map.containsKey(id))
            map[id] = Entry(value, now + ttlMs)
            id
        }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(id: String): T? =
        synchronized(map) {
            val entry = map[id]
            if (entry == null || entry.expiresAt <= clock()) {
                map.remove(id)
                throw staleCursor()
            }
            entry.value as T
        }

    val size: Int get() = synchronized(map) { map.size }

    private fun newId(): String = buildString { repeat(ID_LENGTH) { append(Character.forDigit(random.nextInt(36), 36)) } }

    private fun staleCursor() = ApiException("bad_request", "stale cursor", retryable = true)

    private companion object {
        const val ID_LENGTH = 12
    }
}

/** Process-wide cursor store (64 entries, 10 minutes). */
object PageCursors {
    private val store = PageCursorStore()

    fun put(value: Any): String = store.put(value)

    fun <T> get(id: String): T? = store.get(id)
}
