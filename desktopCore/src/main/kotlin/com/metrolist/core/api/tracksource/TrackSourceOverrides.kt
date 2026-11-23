package com.metrolist.core.api.tracksource

import com.metrolist.core.api.sources.SourcePrefs
import com.metrolist.core.api.sources.SourcesMapper
import com.metrolist.core.api.sources.TrackOverride
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import java.util.concurrent.ConcurrentHashMap

/**
 * The per-track source choice (`track.setSource`) applied to songs about to be played. INT-W4 adds one call in
 * `PlayerApi.resolve`: `TrackSourceOverrides.applyAsync(songs)` (the resolver is suspend). [apply] is the cache-only variant.
 */
object TrackSourceOverrides {
    private const val FETCH_TIMEOUT_MS = 3_000L

    // The matched songs by their own id. A song is remembered when track.setSource picks it; after a restart it is fetched again.
    private val matched = ConcurrentHashMap<String, SongItem>()

    fun remember(song: SongItem) {
        matched[song.id] = song
    }

    /** Replaces each song that has an override and whose matched song is known; every other song passes through. */
    fun apply(songs: List<SongItem>): List<SongItem> = applyWith(songs, SourcePrefs.trackOverrides) { matched[it.id] }

    /** Like [apply], but fetches a matched song that is not known yet (3 s limit; on failure the original song plays). */
    suspend fun applyAsync(songs: List<SongItem>): List<SongItem> {
        val overrides = SourcePrefs.trackOverrides
        if (overrides.isEmpty()) return songs
        val registry = KoinPlatform.getKoinOrNull()?.getOrNull<SourceRegistry>()
        songs.forEach { song ->
            val override = overrides[song.id] ?: return@forEach
            if (matched.containsKey(override.id) || registry == null) return@forEach
            val source = SourcesMapper.parseId(override.source) ?: return@forEach
            try {
                // SourceRegistry.call(id, block): T (throws when the source is disabled or degraded); MusicSource.track(id): SongItem
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { registry.call(source) { it.track(override.id) } }?.let(::remember)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // keep the original song
            }
        }
        return apply(songs)
    }

    /** Pure core of [apply]: [lookup] gives the matched song of an override, or null when it is not known. */
    internal fun applyWith(
        songs: List<SongItem>,
        overrides: Map<String, TrackOverride>,
        lookup: (TrackOverride) -> SongItem?,
    ): List<SongItem> {
        if (overrides.isEmpty()) return songs
        return songs.map { song -> overrides[song.id]?.let(lookup) ?: song }
    }
}
