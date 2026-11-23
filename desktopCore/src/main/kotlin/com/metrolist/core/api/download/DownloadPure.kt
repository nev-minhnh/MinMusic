package com.metrolist.core.api.download

import com.metrolist.desktop.storage.DownloadState
import com.metrolist.desktop.storage.downloadQueueSummary
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files

/** Pure parts of api.md 3.9 (no Koin, no DownloadManager singleton): unit-tested, the handlers only wire them. */

/** `audio/flac` -> "FLAC", `audio/mp4` -> "AAC", `audio/webm` / opus / ogg -> "Opus", mp3 -> "MP3". */
object MimeLabel {
    fun label(mime: String?): String {
        val m = mime?.lowercase().orEmpty()
        return when {
            "flac" in m -> "FLAC"
            "opus" in m || "webm" in m || "ogg" in m -> "Opus"
            "mp3" in m || "mpeg" in m -> "MP3"
            "mp4" in m || "aac" in m || "m4a" in m -> "AAC"
            else -> "Other"
        }
    }
}

/** A finished download as the summary sees it: its mime type and the size on disk (audio plus thumbnail). */
data class FileStat(
    val mimeType: String?,
    val bytes: Long,
)

@Serializable
data class QualityCount(
    val label: String,
    val count: Int,
)

@Serializable
data class ActiveInfo(
    val done: Int,
    val total: Int,
    val label: String? = null,
)

/** `download.summary` result and the `downloads` topic snapshot. */
@Serializable
data class DownloadSummaryDto(
    val count: Int,
    val bytes: Long,
    val byQuality: List<QualityCount>,
    val folder: String,
    val folderOk: Boolean,
    val active: ActiveInfo,
    val paused: Boolean,
)

object SummaryBuilder {
    /**
     * [files] are the finished downloads. `active` replicates the queue summary of the v1 UI: done = finished this
     * session, total = done + queued + downloading + failed (a failed item stays in the batch until retried or dismissed).
     */
    fun build(
        files: List<FileStat>,
        states: Map<String, DownloadState>,
        progresses: Map<String, Float>,
        completed: Int,
        titles: Map<String, String>,
        folder: String,
        folderOk: Boolean,
        paused: Boolean,
    ): DownloadSummaryDto {
        val queue = downloadQueueSummary(states, progresses, completed)
        val running = states.entries.firstOrNull { it.value == DownloadState.DOWNLOADING } ?: states.entries.firstOrNull { it.value == DownloadState.QUEUED }
        return DownloadSummaryDto(
            count = files.size,
            bytes = files.sumOf { it.bytes },
            byQuality =
                files
                    .groupingBy { MimeLabel.label(it.mimeType) }
                    .eachCount()
                    .map { (label, count) -> QualityCount(label, count) }
                    .sortedWith(compareByDescending<QualityCount> { it.count }.thenBy { it.label }),
            folder = folder,
            folderOk = folderOk,
            active =
                if (queue.total == 0) {
                    ActiveInfo(0, 0)
                } else {
                    ActiveInfo(done = completed.coerceAtMost(queue.total), total = queue.total, label = running?.let { titles[it.key] })
                },
            paused = paused,
        )
    }
}

enum class FolderCheck(
    val wire: String?,
) {
    OK(null),
    MISSING("missing"),
    NOT_WRITABLE("not_writable"),
}

object FolderValidate {
    /** `folderOk` of the summary: the folder exists and is writable (false for an unplugged drive). */
    fun isOk(dir: File): Boolean = dir.isDirectory && Files.isWritable(dir.toPath())

    /** A missing directory is `missing`; a directory that cannot take a new file (probe) is `not_writable`. */
    fun check(dir: File): FolderCheck {
        if (!dir.isDirectory) return FolderCheck.MISSING
        val probe =
            try {
                Files.createTempFile(dir.toPath(), ".mm-probe-", ".tmp")
            } catch (_: Exception) {
                return FolderCheck.NOT_WRITABLE
            }
        runCatching { Files.deleteIfExists(probe) }
        return FolderCheck.OK
    }
}

/** Maps a failure message to the codes the UI knows: network, disk_full, not_writable, not_found, unavailable. */
object ErrorLabel {
    fun of(message: String?): String {
        val m = message?.lowercase().orEmpty()
        return when {
            "no space" in m || "disk full" in m -> "disk_full"
            "access is denied" in m || "permission denied" in m || "read-only" in m -> "not_writable"
            "not found" in m || "404" in m -> "not_found"
            "failed to resolve stream" in m || "unavailable" in m || "403" in m || "410" in m || "unsupported download" in m -> "unavailable"
            else -> "network"
        }
    }
}

/** One `download.progress` payload. */
data class ProgressEvent(
    val id: String,
    val state: String,
    val progress: Double,
)

/**
 * Coalesces progress events (api.md 1.9): at most one event per [totalMs] overall (4 Hz) and, for plain progress ticks,
 * one per [perIdMs] per id (2 Hz). A change of state (queued -> downloading -> downloaded/failed) skips the per-id gap,
 * is sent first, and still counts against the total. Only the latest value of an id is kept. Pure: the caller passes the clock.
 */
class ProgressThrottle(
    private val perIdMs: Long = 500,
    private val totalMs: Long = 250,
) {
    private val pending = LinkedHashMap<String, ProgressEvent>()
    private val lastSentAt = HashMap<String, Long>()
    private val lastState = HashMap<String, String>()
    private var lastTotalAt: Long? = null

    @Synchronized
    fun submit(event: ProgressEvent) {
        pending[event.id] = event
    }

    @Synchronized
    fun hasPending(): Boolean = pending.isNotEmpty()

    /** The next event allowed at [now], or null. Call again after the gap for the following one. */
    @Synchronized
    fun poll(now: Long): ProgressEvent? {
        if (pending.isEmpty()) return null
        lastTotalAt?.let { if (now - it < totalMs) return null }
        val pick =
            pending.values.firstOrNull { lastState[it.id] != it.state }
                ?: pending.values.firstOrNull { now - (lastSentAt[it.id] ?: Long.MIN_VALUE / 2) >= perIdMs }
                ?: return null
        pending.remove(pick.id)
        lastTotalAt = now
        if (pick.state == "downloading" || pick.state == "queued") {
            lastSentAt[pick.id] = now
            lastState[pick.id] = pick.state
        } else {
            // terminal for the queue (downloaded, failed, none): nothing more to throttle for this id
            lastSentAt.remove(pick.id)
            lastState.remove(pick.id)
        }
        return pick
    }
}

/** Wire name of a [DownloadState]. */
fun DownloadState.wire(): String =
    when (this) {
        DownloadState.QUEUED -> "queued"
        DownloadState.DOWNLOADING -> "downloading"
        DownloadState.DOWNLOADED -> "downloaded"
        DownloadState.FAILED -> "failed"
        DownloadState.NOT_DOWNLOADED -> "none"
    }

/** Progress events for what changed between two snapshots of the state and progress maps (ids that vanished become `none`). */
object ProgressDiff {
    fun events(
        prevStates: Map<String, DownloadState>,
        prevProgress: Map<String, Float>,
        states: Map<String, DownloadState>,
        progress: Map<String, Float>,
    ): List<ProgressEvent> {
        val out = ArrayList<ProgressEvent>()
        for ((id, state) in states) {
            val p = progress[id]?.toDouble() ?: if (state == DownloadState.DOWNLOADED) 1.0 else 0.0
            if (prevStates[id] != state || prevProgress[id] != progress[id]) out += ProgressEvent(id, state.wire(), p.coerceIn(0.0, 1.0))
        }
        for (id in prevStates.keys) if (id !in states) out += ProgressEvent(id, "none", 0.0)
        return out
    }
}
