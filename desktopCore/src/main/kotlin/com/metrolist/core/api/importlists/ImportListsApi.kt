package com.metrolist.core.api.importlists

import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreLog
import com.metrolist.core.api.library.LibraryChanges
import com.metrolist.core.bridge.AppEvents
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.str
import com.metrolist.core.shell.WebLogin
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.playlistimport.AmazonMusicPlaylistImporter
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.PlaylistImportManager.Destination
import com.metrolist.shared.spotifyimport.SpotifyImportOperationGate
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicLong

/** api.md 3.12 `import.*`: provider sign-in through `shell.webLogin`, playlist lists, and the import job with `import.progress`. */
object ImportListsApi {
    private val jobCounter = AtomicLong(1)

    val handlers: Map<String, Handler> =
        mapOf(
            "import.providers" to handler { _, _ -> providers() },
            "import.login" to handler { params, _ -> login(ImportProviderId.parse(params.str("provider")), params.str("host")) },
            "import.playlists" to handler { params, _ -> playlists(ImportProviderId.parse(params.str("provider"))) },
            "import.run" to handler { params, _ ->
                startImport(ImportProviderId.parse(params.str("provider")), params["playlistIds"], params.str("target"))
            },
        )

    private suspend fun providers(): JsonElement {
        backendReady()
        return buildJsonObject {
            put(
                "items",
                JsonArray(
                    ImportProviderId.entries.map { id ->
                        buildJsonObject {
                            put("id", id.wire)
                            put("signedIn", ImportProviders[id].signedIn)
                        }
                    },
                ),
            )
        }
    }

    /** Opens the provider's login window in the shell, then authenticates the manager with what it returns. */
    private suspend fun login(
        id: ImportProviderId,
        host: String?,
    ): JsonElement {
        if (host != null && host !in AmazonMusicPlaylistImporter.marketplaceHosts) throw ApiException("bad_request", "unknown marketplace '$host'")
        backendReady()
        val config = ImportLoginConfigs.forProvider(id, host ?: ImportLoginConfigs.DEFAULT_AMAZON_HOST)
        // WebLogin.request(cfg: WebLoginConfig): WebLoginResult (M3-2); the result holds cookies, so it is never logged
        val result = WebLogin.request(config)
        if (result.cancelled) throw ApiException("cancelled", "login cancelled")
        try {
            ImportProviders[id].authenticate(result, host)
        } catch (error: Exception) {
            throw mapImportError(error)
        }
        return emptyResult()
    }

    private suspend fun playlists(id: ImportProviderId): JsonElement {
        backendReady()
        val provider = ImportProviders[id]
        if (!provider.signedIn) throw ApiException("not_signed_in", "not signed in to ${id.wire}")
        val rows =
            try {
                provider.listPlaylists()
            } catch (error: Exception) {
                throw mapImportError(error)
            }
        return buildJsonObject { put("items", JsonArray(rows.map(ImportRow::toJson))) }
    }

    private suspend fun startImport(
        id: ImportProviderId,
        rawIds: JsonElement?,
        rawTarget: String?,
    ): JsonElement {
        backendReady()
        val provider = ImportProviders[id]
        if (!provider.signedIn) throw ApiException("not_signed_in", "not signed in to ${id.wire}")
        val playlistIds = parsePlaylistIds(rawIds)
        val target = parseImportTarget(rawTarget)
        val database = GlobalContext.get().get<MusicDatabase>()
        val existing: PlaylistEntity? =
            when (target) {
                ImportTarget.NewUserAlbum -> {
                    null
                }

                is ImportTarget.Existing -> {
                    database.dao().playlist(target.playlistId).first()?.playlist?.takeIf { it.isLocal && it.isEditable }
                        ?: throw ApiException("bad_request", "target playlist not found or not editable")
                }
            }
        // SpotifyImportOperationGate.tryStart(): Boolean / finish(): one import at a time, as in v1
        if (!SpotifyImportOperationGate.tryStart()) throw ApiException("busy", "another import is running", retryable = true)
        val jobId = "imp-${jobCounter.getAndIncrement()}"
        CoreBackend.scope.launch(Dispatchers.IO) {
            try {
                runJob(jobId, id, provider, playlistIds, target, existing, database)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                CoreLog.error("import job $jobId failed: ${error::class.simpleName}")
            } finally {
                SpotifyImportOperationGate.finish()
            }
        }
        return buildJsonObject { put("jobId", jobId) }
    }

    /**
     * Phase 1 loads every selected playlist (so `total` is exact), phase 2 resolves each one against YouTube Music and imports
     * it into a new user album or the existing playlist. The last event is always sent, with `failedTracks` and `albumId`.
     */
    private suspend fun runJob(
        jobId: String,
        id: ImportProviderId,
        provider: ImportProvider,
        playlistIds: List<String>,
        target: ImportTarget,
        existing: PlaylistEntity?,
        database: MusicDatabase,
    ) {
        val aggregate = ProgressAggregate(playlistIds.map { provider.known(it)?.count ?: 0 })
        val throttle = ProgressThrottle()
        val createdAlbums = mutableListOf<String>()
        var changed = false

        fun emit(final: Boolean = false) {
            val snapshot = aggregate.snapshot(final)
            if (!final && snapshot.total == 0) return
            if (!throttle.tryPass(System.currentTimeMillis(), force = final)) return
            AppEvents.emit(
                "import.progress",
                if (final) {
                    progressPayload(jobId, snapshot, aggregate.failedTracks, resultAlbumId(target, createdAlbums))
                } else {
                    progressPayload(jobId, snapshot)
                },
            )
        }
        try {
            emit()
            val loaded = arrayOfNulls<LoadedImport>(playlistIds.size)
            playlistIds.forEachIndexed { index, playlistId ->
                try {
                    val data = provider.load(playlistId)
                    loaded[index] = data
                    aggregate.setTotal(index, data.tracks.size)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    CoreLog.error("import: loading a ${id.wire} playlist failed: ${error::class.simpleName}")
                    aggregate.markLoadFailed(index, provider.known(playlistId)?.title ?: playlistId)
                }
                emit()
            }

            val manager = PlaylistImportManager(database, GlobalYouTubeRepository.instance)
            loaded.forEachIndexed { index, data ->
                if (data == null) return@forEachIndexed
                val destination = if (existing != null) Destination.EXISTING(existing) else Destination.NewLocal
                try {
                    // resolveImportTracks(tracks: List<CsvTrack>, destination: Destination?, provider: ImportSourceType?,
                    //     onProgress: (resolved: Int, total: Int, currentTrack: CsvTrack?) -> Unit): List<Pair<CsvTrack, SongItem>>
                    val matches =
                        manager.resolveImportTracks(data.tracks, destination, id.source) { resolved, _, _ ->
                            aggregate.setDone(index, resolved)
                            emit()
                        }
                    val matched = matches.map { it.first }
                    aggregate.addFailed(CsvTrackMapper.unresolvedLabels(data.tracks, matched))
                    if (matches.isNotEmpty()) {
                        // importResolvedTracks(name, matches, destination, provider, sourceYouTubePlaylistId = null, coverUrl = null,
                        //     onProgress: (imported: Int, total: Int, currentSong: SongItem?) -> Unit): Result<ImportResult>
                        val result =
                            manager.importResolvedTracks(data.title, matches, destination, id.source, coverUrl = data.coverUrl) { _, _, _ -> }
                        val playlist =
                            result.getOrNull()?.playlist
                                ?: (result.exceptionOrNull() as? PlaylistImportManager.IncompleteImportException)?.playlist
                        if (playlist != null) {
                            changed = true
                            if (existing == null) {
                                markUserAlbum(database, playlist)
                                createdAlbums += playlist.id
                            }
                        }
                        result.exceptionOrNull()?.let { error ->
                            CoreLog.error("import: saving a ${id.wire} playlist failed: ${error::class.simpleName}")
                            val missing = (error as? PlaylistImportManager.IncompleteImportException)?.failedSongCount ?: matched.size
                            aggregate.addFailed(matched.takeLast(missing).map(CsvTrackMapper::label))
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    CoreLog.error("import: a ${id.wire} playlist failed: ${error::class.simpleName}")
                    aggregate.addFailed(listOf(data.title))
                }
                aggregate.complete(index)
                emit()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CoreLog.error("import job $jobId aborted: ${error::class.simpleName}")
        } finally {
            if (changed) bumpLibrary(existing, createdAlbums)
        }
        emit(final = true)
    }

    /** `createUserAlbum` marks a local playlist with `isUserAlbum`; the import creates the playlist itself, so it is marked after. */
    private suspend fun markUserAlbum(
        database: MusicDatabase,
        playlist: PlaylistEntity,
    ) {
        val current = database.dao().playlist(playlist.id).first()?.playlist ?: playlist
        database.dao().update(current.copy(isUserAlbum = true))
    }

    private fun bumpLibrary(
        existing: PlaylistEntity?,
        createdAlbums: List<String>,
    ) {
        val ids = if (existing != null) listOf(existing.id) else createdAlbums
        // LibraryChanges.bump(lists: Collection<String>, userAlbums: Boolean = false): Long (M1-2b)
        LibraryChanges.bump(listOf("playlists") + ids.map { "userAlbum:lp:$it" }, userAlbums = true)
    }
}
