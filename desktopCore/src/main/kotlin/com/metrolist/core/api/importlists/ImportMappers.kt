package com.metrolist.core.api.importlists

import com.metrolist.core.server.ApiException
import com.metrolist.core.server.badRequest
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.PlaylistImportManager.CsvTrack
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import meow.nyx.spotlib.SpotifyAuthException

/** The five providers of api.md 3.12, in the order `import.providers` lists them. */
enum class ImportProviderId(
    val wire: String,
    val source: ImportSourceType,
) {
    SPOTIFY("spotify", ImportSourceType.SPOTIFY),
    TIDAL("tidal", ImportSourceType.TIDAL),
    DEEZER("deezer", ImportSourceType.DEEZER),
    APPLE("apple", ImportSourceType.APPLE_MUSIC),
    AMAZON("amazon", ImportSourceType.AMAZON),
    ;

    companion object {
        /** Wire name to provider; anything else is `bad_request`. */
        fun parse(raw: String?): ImportProviderId = entries.firstOrNull { it.wire == raw } ?: badRequest("unknown provider '${raw.orEmpty()}'")
    }
}

/** Where an import goes (api.md 3.12 `target`): a new user album per playlist, or an existing local playlist. */
sealed interface ImportTarget {
    data object NewUserAlbum : ImportTarget

    /** [playlistId] is the database id, without the `lp:` prefix. */
    data class Existing(
        val playlistId: String,
    ) : ImportTarget
}

private const val LP_PREFIX = "lp:"

fun parseImportTarget(raw: String?): ImportTarget =
    when {
        raw == "newUserAlbum" -> ImportTarget.NewUserAlbum
        raw != null && raw.startsWith(LP_PREFIX) && raw.length > LP_PREFIX.length -> ImportTarget.Existing(raw.removePrefix(LP_PREFIX))
        else -> badRequest("target must be 'newUserAlbum' or 'lp:<id>'")
    }

/** `playlistIds`: a non-empty array of strings; duplicates are dropped, order is kept. */
fun parsePlaylistIds(raw: JsonElement?): List<String> {
    val array = raw as? JsonArray ?: badRequest("playlistIds: non-empty string[] required")
    val ids =
        array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: badRequest("playlistIds: non-empty string[] required")
        }
    if (ids.isEmpty()) badRequest("playlistIds: non-empty string[] required")
    return ids.distinct()
}

/** One row of `import.playlists`. [count] is what the provider reported; liked-song lists may report 0. */
data class ImportRow(
    val id: String,
    val title: String,
    val count: Int,
    val art: String?,
) {
    fun toJson(): JsonElement =
        buildJsonObject {
            put("id", id)
            put("title", title)
            put("count", count)
            if (!art.isNullOrBlank()) put("art", art)
        }
}

/** Provider tracks to the `CsvTrack`s that `PlaylistImportManager` resolves against YouTube Music. */
object CsvTrackMapper {
    fun fromSpotify(track: SpotifyImportManager.ImportTrack): CsvTrack =
        CsvTrack(
            title = track.title,
            artist = track.artist,
            videoId = null,
            album = track.album,
            durationSeconds = track.durationSeconds,
        )

    /** "Artist - Title", or just the title when the provider gave no artist. This is what `failedTracks` carries. */
    fun label(track: CsvTrack): String {
        val title = track.title.trim()
        val artist = track.artist.trim()
        return if (artist.isEmpty()) title else "$artist - $title"
    }

    /** Labels of the tracks `resolveImportTracks` could not match (duplicates are paired one to one). */
    fun unresolvedLabels(
        tracks: List<CsvTrack>,
        matched: List<CsvTrack>,
    ): List<String> {
        val remaining = matched.groupingBy { it }.eachCount().toMutableMap()
        return tracks
            .filter { track ->
                val left = remaining[track] ?: 0
                if (left > 0) remaining[track] = left - 1
                left == 0
            }.map(::label)
    }
}

data class ProgressSnapshot(
    val done: Int,
    val total: Int,
    val failed: Int,
)

/**
 * Folds per-playlist progress into the one `{done, total, failed}` of an `import.progress` event.
 * Rules the UI relies on: `done` never goes down, `failed <= done`, and `done` stays below `total` until the final
 * snapshot, because the UI treats `done >= total` as "the job is over".
 */
class ProgressAggregate(
    estimates: List<Int>,
) {
    private val totals = IntArray(estimates.size) { estimates[it].coerceAtLeast(0) }
    private val dones = IntArray(estimates.size)
    private val failedLabels = mutableListOf<String>()
    private var reportedDone = 0

    val failedTracks: List<String> get() = failedLabels.toList()

    /** The real track count of playlist [index], known once it is loaded. */
    fun setTotal(
        index: Int,
        total: Int,
    ) {
        totals[index] = total.coerceAtLeast(0)
        dones[index] = dones[index].coerceAtMost(totals[index])
    }

    /** Tracks of playlist [index] processed so far. */
    fun setDone(
        index: Int,
        done: Int,
    ) {
        dones[index] = maxOf(dones[index], done.coerceIn(0, totals[index]))
    }

    fun complete(index: Int) {
        dones[index] = totals[index]
    }

    fun addFailed(labels: List<String>) {
        failedLabels += labels
    }

    /** A playlist that could not be loaded counts as one finished, failed item named [label]. */
    fun markLoadFailed(
        index: Int,
        label: String,
    ) {
        totals[index] = maxOf(totals[index], 1)
        dones[index] = totals[index]
        failedLabels += label
    }

    fun snapshot(final: Boolean): ProgressSnapshot {
        val total = totals.sum()
        var done = dones.sum()
        if (final) {
            done = total
        } else {
            done = minOf(maxOf(done, reportedDone), (total - 1).coerceAtLeast(0))
        }
        reportedDone = maxOf(reportedDone, done)
        return ProgressSnapshot(done, total, minOf(failedLabels.size, done))
    }
}

/** Lets one event per [minIntervalMs] through (4 Hz); a forced one (the final event) always passes. */
class ProgressThrottle(
    private val minIntervalMs: Long = 250,
) {
    private var last: Long? = null

    fun tryPass(
        nowMs: Long,
        force: Boolean = false,
    ): Boolean {
        val previous = last
        if (!force && previous != null && nowMs - previous < minIntervalMs) return false
        last = nowMs
        return true
    }
}

/** The `import.progress` payload. The final one also carries `failedTracks` and the result `albumId`. */
fun progressPayload(
    jobId: String,
    snapshot: ProgressSnapshot,
    failedTracks: List<String>? = null,
    albumId: String? = null,
): JsonElement =
    buildJsonObject {
        put("jobId", jobId)
        put("done", snapshot.done)
        put("total", snapshot.total)
        put("failed", snapshot.failed)
        if (failedTracks != null) put("failedTracks", JsonArray(failedTracks.map { JsonPrimitive(it) }))
        if (albumId != null) put("albumId", albumId)
    }

/** The user album the UI opens at the end: the first album of the run. An existing target is that album itself. */
fun resultAlbumId(
    target: ImportTarget,
    createdPlaylistIds: List<String>,
): String? =
    when (target) {
        is ImportTarget.Existing -> LP_PREFIX + target.playlistId
        ImportTarget.NewUserAlbum -> createdPlaylistIds.firstOrNull()?.let { LP_PREFIX + it }
    }

/** A provider or network failure as an API error. Messages never include cookies or tokens. */
fun mapImportError(error: Throwable): ApiException =
    when {
        error is ApiException -> error
        error is CancellationException -> throw error
        error is SpotifyAuthException -> ApiException("not_signed_in", "provider session rejected")
        error is IllegalStateException && error.message.orEmpty().startsWith("Sign in to") -> ApiException("not_signed_in", error.message.orEmpty())
        error is java.io.IOException -> ApiException("network", "network error: ${error::class.simpleName}", retryable = true)
        else -> ApiException("internal", "import failed: ${error::class.simpleName}")
    }
