package com.metrolist.core.api.download

import com.metrolist.core.api.PlayerApi
import com.metrolist.core.api.Track
import com.metrolist.core.api.toJson
import com.metrolist.core.api.toTrack
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.bool
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.int
import com.metrolist.core.server.notFound
import com.metrolist.core.server.obj
import com.metrolist.core.server.requireBool
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.str
import com.metrolist.core.server.topic
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.DownloadState
import com.metrolist.desktop.storage.DownloadedFile
import com.metrolist.desktop.storage.UserAlbumExport
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.source.sourceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * api.md 3.9 downloads. Everything delegates to the v1 [DownloadManager] (best stream without re-encode, tags, folders);
 * the mapping lives in the pure helpers of this package.
 */
object DownloadApi {
    private val dao: DatabaseDao get() = GlobalContext.get().get()

    /** Pokes the `downloads` topic when something outside the manager flows changed (the folder). */
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** File sizes by path: a finished download never changes, so a size is read once. */
    private val sizes = ConcurrentHashMap<String, Long>()

    val handlers: Map<String, Handler> =
        mapOf(
            "download.tracks" to handler { p, _ -> tracks(p) },
            "download.context" to handler { p, _ -> context(p) },
            "download.cancel" to handler { p, _ -> cancel(p) },
            "download.pause" to handler { _, _ -> setPaused(true) },
            "download.resume" to handler { _, _ -> setPaused(false) },
            "download.retry" to handler { p, _ -> retry(p) },
            "download.remove" to handler { p, _ -> remove(p) },
            "download.summary" to
                handler { _, _ ->
                    backendReady()
                    withContext(Dispatchers.IO) { summary().toJson() }
                },
            "download.window" to handler { p, _ -> window(p) },
            "download.queue" to handler { _, _ -> queue() },
            "download.setFolder" to handler { p, _ -> setFolder(p) },
            "download.saveTo" to handler { p, _ -> saveTo(p) },
        )

    // --- helpers --------------------------------------------------------------------------------------------------

    private fun idList(
        params: JsonObject,
        key: String,
    ): List<String>? {
        val raw = params[key] ?: return null
        val array = raw as? JsonArray ?: badRequest("'$key' must be an array of ids")
        return array.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank) ?: badRequest("'$key' must hold non-empty strings") }
    }

    /** The finished downloads: catalog entries whose file is present (the manager marks those DOWNLOADED at start). */
    private fun finished(): List<DownloadedFile> {
        val states = DownloadManager.downloadStates.value
        return DownloadManager.downloadedFiles.value.filter { states[it.songId] == DownloadState.DOWNLOADED }
    }

    private fun bytesOf(path: String?): Long {
        if (path == null) return 0
        sizes[path]?.let { return it }
        val n = runCatching { File(path).length() }.getOrDefault(0L)
        if (n > 0) sizes[path] = n
        return n
    }

    /** Blocking (file sizes): call on IO. */
    private fun summary(): DownloadSummaryDto {
        val folder = DownloadManager.getDownloadDir()
        return SummaryBuilder.build(
            files = finished().map { FileStat(it.mimeType, bytesOf(it.audioFilePath) + bytesOf(it.thumbnailFilePath)) },
            states = DownloadManager.downloadStates.value,
            progresses = DownloadManager.downloadProgress.value,
            completed = DownloadManager.sessionCompletedCount.value,
            titles = DownloadManager.downloadTitles.value,
            folder = folder.absolutePath,
            folderOk = FolderValidate.isOk(folder),
            paused = DownloadManager.paused.value,
        )
    }

    /** Bytes of all finished downloads (audio plus thumbnails), for `storage.usage`. Blocking: call on IO. */
    internal fun downloadsBytes(): Long = finished().sumOf { bytesOf(it.audioFilePath) + bytesOf(it.thumbnailFilePath) }

    /** A song id that came back as the bare fallback of [PlayerApi.resolve] (no library row, no remote metadata). */
    internal fun isBare(song: SongItem): Boolean = song.title == song.id && song.artists.isEmpty()

    // --- commands -------------------------------------------------------------------------------------------------

    private suspend fun tracks(params: JsonObject): JsonElement {
        backendReady()
        val ids = idList(params, "ids")?.takeIf { it.isNotEmpty() } ?: badRequest("missing 'ids'")
        ids.firstOrNull { !DownloadManager.isValidDownloadSongId(it) }?.let { badRequest("invalid track id '$it'") }
        // resolve(ids): library rows first, YouTube metadata for the rest, a bare item as the last resort
        val songs = PlayerApi.resolve(ids)
        songs.firstOrNull(::isBare)?.let { notFound("track ${it.id}") }
        // downloadAll(songs: List<SongItem>)
        DownloadManager.downloadAll(songs)
        return emptyResult()
    }

    private suspend fun context(params: JsonObject): JsonElement {
        backendReady()
        val context = params.obj("context") ?: badRequest("missing 'context'")
        // resolveContext(context: JsonObject): List<SongItem>
        val songs = PlayerApi.resolveContext(context)
        val id = context.str("id")
        when (context.str("kind")) {
            // downloadPlaylist(playlistId: String, songs: List<SongItem>): also keeps the list in sync (v1 behaviour)
            "playlist" -> DownloadManager.downloadPlaylist(id ?: badRequest("missing context.id"), songs)
            "userAlbum" -> DownloadManager.downloadPlaylist((id ?: badRequest("missing context.id")).removePrefix("lp:"), songs)
            else -> DownloadManager.downloadAll(songs)
        }
        return emptyResult()
    }

    private suspend fun cancel(params: JsonObject): JsonElement {
        backendReady()
        val all = params.bool("all") ?: false
        val ids = idList(params, "ids").orEmpty()
        if (!all && ids.isEmpty()) badRequest("ids or all required")
        // stopActiveDownloads() / cancelDownloads(ids: Collection<String>) (new)
        if (all) DownloadManager.stopActiveDownloads() else DownloadManager.cancelDownloads(ids)
        return emptyResult()
    }

    private suspend fun setPaused(value: Boolean): JsonElement {
        backendReady()
        DownloadManager.paused.value = value
        return emptyResult()
    }

    private suspend fun retry(params: JsonObject): JsonElement {
        backendReady()
        val ids = idList(params, "ids") ?: DownloadManager.downloadStates.value.filterValues { it == DownloadState.FAILED }.keys.toList()
        // retryFailedDownload(songId: String)
        ids.forEach(DownloadManager::retryFailedDownload)
        return emptyResult()
    }

    private suspend fun remove(params: JsonObject): JsonElement {
        backendReady()
        val ids = idList(params, "ids")?.takeIf { it.isNotEmpty() } ?: badRequest("missing 'ids'")
        if (!params.requireBool("deleteFiles")) badRequest("deleteFiles:true required")
        // suspend deleteDownloadsAndAwait(songIds: Collection<String>): files and catalog rows are gone when it returns
        DownloadManager.deleteDownloadsAndAwait(ids)
        return emptyResult()
    }

    private suspend fun setFolder(params: JsonObject): JsonElement {
        backendReady()
        if ("path" !in params) badRequest("path: string | null required")
        val dir =
            when (val raw = params["path"]) {
                is JsonNull -> null
                is JsonPrimitive -> raw.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)?.let(::File) ?: badRequest("path must be a non-empty string or null")
                else -> badRequest("path: string | null required")
            }
        if (dir != null) {
            if (!dir.isAbsolute) badRequest("path must be absolute")
            val check = withContext(Dispatchers.IO) { FolderValidate.check(dir) }
            check.wire?.let { code ->
                return buildJsonObject {
                    put("ok", false)
                    put("error", code)
                }
            }
        }
        // changeDownloadDirectory(directory: File?): null = the default folder
        withContext(Dispatchers.IO) { DownloadManager.changeDownloadDirectory(dir) }
        kick.tryEmit(Unit)
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun saveTo(params: JsonObject): JsonElement {
        backendReady()
        val ids = idList(params, "ids")?.takeIf { it.isNotEmpty() } ?: badRequest("missing 'ids'")
        val folder = File(params.requireStr("path"))
        if (!folder.isAbsolute) badRequest("path must be absolute")
        // Optional extra: `userAlbum` ("lp:<id>") exports the songs as that user album (ALBUM, cover, track numbers).
        val export =
            params.str("userAlbum")?.let { raw ->
                val playlist = withContext(Dispatchers.IO) { dao.playlist(raw.removePrefix("lp:")).first() } ?: notFound("user album $raw")
                UserAlbumExport(name = playlist.title, artworkUrl = playlist.thumbnailUrl)
            }
        // suspend saveDownloadsToFolder(songIds, folder, userAlbum): SaveToFolderResult(saved, failed)
        val result =
            try {
                DownloadManager.saveDownloadsToFolder(ids, folder, export)
            } catch (e: IllegalArgumentException) {
                badRequest(e.message ?: "folder unavailable")
            }
        return buildJsonObject {
            put("saved", result.saved)
            put("failed", result.failed)
        }
    }

    // --- reads ----------------------------------------------------------------------------------------------------

    private suspend fun queue(): JsonElement {
        backendReady()
        val states = DownloadManager.downloadStates.value
        val progress = DownloadManager.downloadProgress.value
        val errors = DownloadManager.downloadErrors.value
        val titles = DownloadManager.downloadTitles.value
        val live = states.filterValues { it == DownloadState.QUEUED || it == DownloadState.DOWNLOADING || it == DownloadState.FAILED }
        val stored = withContext(Dispatchers.IO) { dao.getSongsByIds(live.keys.toList()).associateBy { it.id } }
        val items =
            live.map { (id, state) ->
                val track = stored[id]?.toTrack() ?: Track(id = id, title = titles[id] ?: id, artists = emptyList(), source = sourceOf(id).key)
                QueueItemMapper.item(
                    track,
                    state.wire(),
                    (progress[id] ?: 0f).toDouble(),
                    if (state == DownloadState.FAILED) ErrorLabel.of(errors[id]) else null,
                )
            }
        return buildJsonObject { put("items", JsonArray(items)) }
    }

    private class RowsCache(
        val files: List<DownloadedFile>,
        val doneCount: Int,
        val at: Long,
        val rows: List<DownloadRow>,
    )

    @Volatile
    private var rowsCache: RowsCache? = null

    /** The finished downloads as window rows: library rows where known, the catalog entry otherwise. Cached for 2 s per catalog state. */
    private suspend fun rows(): List<DownloadRow> {
        val files = DownloadManager.downloadedFiles.value
        val states = DownloadManager.downloadStates.value
        val doneCount = states.count { it.value == DownloadState.DOWNLOADED }
        val now = System.currentTimeMillis()
        rowsCache?.let { c -> if (c.files === files && c.doneCount == doneCount && now - c.at < ROWS_TTL_MS) return c.rows }
        val done = files.filter { states[it.songId] == DownloadState.DOWNLOADED }
        val stored = withContext(Dispatchers.IO) { dao.getSongsByIds(done.map { it.songId }).associateBy { it.id } }
        val rows =
            done.map { f ->
                val song = stored[f.songId]
                DownloadRow(
                    track = song?.toTrack()?.copy(download = "downloaded") ?: f.toSongItem().toTrack(download = "downloaded"),
                    downloadedAt = f.downloadDate,
                    plays = song?.song?.totalPlayTime ?: 0L,
                )
            }
        rowsCache = RowsCache(files, doneCount, now, rows)
        return rows
    }

    private suspend fun window(params: JsonObject): JsonElement {
        backendReady()
        if (params.str("list") != "downloaded") badRequest("list must be downloaded")
        val offset = params.int("offset") ?: 0
        val limit = params.int("limit") ?: badRequest("missing number param 'limit'")
        if (offset < 0 || limit !in 1..MAX_WINDOW) badRequest("offset >= 0 and limit 1..$MAX_WINDOW required")
        val sort = params.obj("sort") ?: badRequest("missing 'sort'")
        val filter =
            params.obj("filter")?.let {
                DownloadFilter(text = it.str("text"), source = it.str("source"), liked = it.bool("liked"), explicit = it.bool("explicit"))
            } ?: DownloadFilter()
        return DownloadWindow.window(rows(), sort.requireStr("by"), sort.requireBool("desc"), filter, offset, limit)
    }

    // --- topic ----------------------------------------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    val downloadsTopic: Topic =
        topic { _, emit ->
            backendReady()
            coroutineScope {
                var last = withContext(Dispatchers.IO) { summary() }
                emit("downloads.snapshot", last.toJson())

                // download.summary on any change of the counts, the queue, the pause flag or the folder, at most every 500 ms
                launch {
                    val changes: Flow<Unit> =
                        merge(
                            combine(
                                DownloadManager.downloadedFiles,
                                DownloadManager.downloadStates,
                                DownloadManager.downloadTitles,
                                DownloadManager.sessionCompletedCount,
                                DownloadManager.paused,
                            ) { _, _, _, _, _ -> },
                            kick,
                        )
                    changes.sample(SUMMARY_SAMPLE_MS).collect {
                        val next = withContext(Dispatchers.IO) { summary() }
                        if (next != last) {
                            last = next
                            emit("download.summary", next.toJson())
                        }
                    }
                }

                // download.progress, coalesced to 2 Hz per id and 4 Hz overall
                val throttle = ProgressThrottle()
                launch {
                    var prevStates = DownloadManager.downloadStates.value
                    var prevProgress = DownloadManager.downloadProgress.value
                    combine(DownloadManager.downloadStates, DownloadManager.downloadProgress) { s, p -> s to p }.collect { (s, p) ->
                        ProgressDiff.events(prevStates, prevProgress, s, p).forEach(throttle::submit)
                        prevStates = s
                        prevProgress = p
                    }
                }
                launch {
                    while (isActive) {
                        val event = throttle.poll(System.currentTimeMillis())
                        if (event != null) {
                            emit(
                                "download.progress",
                                buildJsonObject {
                                    put("id", event.id)
                                    put("state", event.state)
                                    put("progress", event.progress)
                                },
                            )
                        } else {
                            delay(if (throttle.hasPending()) 40 else 200)
                        }
                    }
                }
            }
        }

    private const val MAX_WINDOW = 200
    private const val SUMMARY_SAMPLE_MS = 500L
    private const val ROWS_TTL_MS = 2_000L
}
