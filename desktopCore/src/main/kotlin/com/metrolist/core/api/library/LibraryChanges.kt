package com.metrolist.core.api.library

import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreLog
import com.metrolist.shared.db.MusicDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicLong

/** What changed at which revision; [userAlbums] asks for `userAlbums.changed`. [lists] are `LocalList` strings. */
data class LibraryChange(
    val lists: Set<String>,
    val revision: Long,
    val userAlbums: Boolean = false,
)

/**
 * The library revision (api.md 1.8B): one global counter, bumped for every change batch. It is the revision of every
 * window and the base of `library.changed`. Writes made through this API call [bump] themselves (so the answer already
 * carries the new revision); a background watcher on the database tables bumps for every other writer (the player's
 * history, downloads, remote sync), debounced and folded into a recent explicit bump.
 */
object LibraryChanges {
    val ALL_LISTS: List<String> = listOf("songs", "liked", "downloaded", "cached", "albums", "artists", "playlists", "history")

    private val counter = AtomicLong(1)
    private val _events = MutableSharedFlow<LibraryChange>(extraBufferCapacity = 256)

    @Volatile
    private var lastExplicitAt = 0L

    @Volatile
    private var started = false

    val events: SharedFlow<LibraryChange> = _events.asSharedFlow()
    val revision: Long get() = counter.get()

    /** Bumps the revision and announces the change. Returns the new revision. */
    fun bump(
        lists: Collection<String>,
        userAlbums: Boolean = false,
    ): Long {
        lastExplicitAt = System.currentTimeMillis()
        return publish(lists, userAlbums)
    }

    private fun publish(
        lists: Collection<String>,
        userAlbums: Boolean,
    ): Long {
        val next = counter.incrementAndGet()
        _events.tryEmit(LibraryChange(lists.toSet(), next, userAlbums))
        return next
    }

    /** Starts the table watcher once the database exists. Safe to call from every handler. */
    @OptIn(FlowPreview::class)
    fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        CoreBackend.scope.launch(Dispatchers.IO) {
            try {
                val database = GlobalContext.get().get<MusicDatabase>()
                // createFlow(vararg tables): emits when any of the tables changes (and once at the start).
                database.invalidationTracker
                    .createFlow(*WATCHED_TABLES)
                    .debounce(OBSERVED_DEBOUNCE_MS)
                    .collect {
                        if (System.currentTimeMillis() - lastExplicitAt >= FOLD_MS) publish(ALL_LISTS, userAlbums = true)
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                started = false
                CoreLog.error("library change watcher stopped", error)
            }
        }
    }

    private val WATCHED_TABLES =
        arrayOf(
            "song", "album", "artist", "playlist", "playlist_song_map", "event", "history_snapshot",
            "song_artist_map", "song_album_map", "album_artist_map",
        )
    private const val OBSERVED_DEBOUNCE_MS = 300L

    /** An observed change this soon after an explicit bump is the echo of that write and adds nothing. */
    private const val FOLD_MS = 600L
}

/**
 * Turns a stream of changes into `library.changed` emissions of at most one per [minIntervalMs] (api.md 1.9, 4 Hz):
 * the first change goes out at once, later ones are merged and go out when the interval has passed. Pure: the caller
 * passes the clock.
 */
class ChangeCoalescer(
    private val minIntervalMs: Long = 250,
) {
    data class Emission(
        val lists: List<String>,
        val revision: Long,
        val userAlbums: Boolean,
    )

    private val lists = LinkedHashSet<String>()
    private var userAlbums = false
    private var revision = 0L
    private var pending = false
    private var lastEmitAt: Long? = null

    @Synchronized
    fun add(change: LibraryChange) {
        pending = true
        lists += change.lists
        userAlbums = userAlbums || change.userAlbums
        revision = maxOf(revision, change.revision)
    }

    @Synchronized
    fun hasPending(): Boolean = pending

    /** Milliseconds to wait before [flush] may emit; 0 when it may emit now or nothing is pending. */
    @Synchronized
    fun waitMs(now: Long): Long {
        val last = lastEmitAt ?: return 0
        return if (!pending) 0 else maxOf(0, last + minIntervalMs - now)
    }

    @Synchronized
    fun flush(now: Long): Emission? {
        if (!pending) return null
        val emission = Emission(lists.toList(), revision, userAlbums)
        lists.clear()
        userAlbums = false
        pending = false
        lastEmitAt = now
        return emission
    }
}

/**
 * Remembers which content of a user album a window saw at which revision, so `userAlbum.move` reports `conflict` only
 * when the album really changed since the client's window (a global revision bump caused by another list is harmless).
 */
class AlbumRevisionLog(
    private val capacity: Int = 512,
) {
    private val seen =
        object : LinkedHashMap<Pair<String, Long>, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, Long>?): Boolean = size > capacity
        }

    @Synchronized
    fun record(
        albumId: String,
        revision: Long,
        signature: Long,
    ) {
        seen[albumId to revision] = signature
    }

    /**
     * True when a write built on [expectedRevision] is out of date. If a window of that revision was served, the album's
     * content signature decides; otherwise the revision itself must still be current.
     */
    @Synchronized
    fun isStale(
        albumId: String,
        expectedRevision: Long,
        currentRevision: Long,
        currentSignature: Long,
    ): Boolean {
        val known = seen[albumId to expectedRevision]
        return if (known != null) known != currentSignature else expectedRevision != currentRevision
    }

    companion object {
        /** Order-sensitive signature of an album's entry ids. */
        fun signature(entryIds: List<Int>): Long = entryIds.fold(17L) { acc, id -> acc * 31 + id }
    }
}
