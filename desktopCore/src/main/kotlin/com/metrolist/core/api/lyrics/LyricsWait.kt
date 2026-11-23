package com.metrolist.core.api.lyrics

/**
 * Decides when `lyrics.get` (and the first `lyrics` snapshot) can answer. The desktop LyricsState only fetches on its
 * own while the v1 lyrics drawer is open, so the core starts the fetch and polls its flags: the lyrics are ready when
 * entries are there and loading is off; a miss is when loading ran and ended without entries (or never started within
 * [NO_LOAD_GRACE_MS], e.g. a cached miss).
 */
object LyricsWait {
    const val NO_LOAD_GRACE_MS = 2_500L

    enum class Decision { WAIT, READY, MISS }

    fun decide(
        forThisSong: Boolean,
        entryCount: Int,
        loading: Boolean,
        sawLoading: Boolean,
        elapsedMs: Long,
    ): Decision =
        when {
            !forThisSong -> Decision.WAIT
            entryCount > 0 && !loading -> Decision.READY
            loading -> Decision.WAIT
            sawLoading || elapsedMs >= NO_LOAD_GRACE_MS -> Decision.MISS
            else -> Decision.WAIT
        }
}
