package com.metrolist.android.playback

import com.metrolist.shared.network.sharedOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal data class HlsProgress(
    val segmentsDone: Int,
    val segmentsTotal: Int,
    val bytes: Long,
)

/**
 * Downloads an unencrypted HLS audio playlist (fMP4 or MPEG-TS segments) and remuxes it to a tagged M4A without re-encoding.
 * Throws [HlsException] subtypes; cancel by cancelling the calling coroutine.
 */
internal suspend fun downloadHlsAudioToM4a(
    playlistUrl: String,
    target: File,
    metadata: AudioExportMetadata,
    tempDir: File,
    headers: Map<String, String> = emptyMap(),
    maxBytes: Long = 512L * 1024 * 1024,
    client: OkHttpClient = sharedOkHttpClient,
    onProgress: (HlsProgress) -> Unit = {},
    exportAac: (File, File, AudioExportMetadata) -> Boolean = ::exportAacM4a,
): File =
    withContext(Dispatchers.IO) {
        var fetched = fetchPlaylist(client, playlistUrl, headers)
        if (fetched is HlsPlaylist.Master) {
            val variant = selectHlsAudioVariant(fetched.variants) ?: throw HlsException.Unsupported("Master playlist has no variants")
            fetched = fetchPlaylist(client, variant.url, headers)
        }
        val playlist = fetched as? HlsPlaylist.Media ?: throw HlsException.Unsupported("Nested master playlists are not supported")
        if (playlist.segmentUrls.isEmpty()) throw HlsException.Unsupported("Playlist has no segments")
        val temp = File(tempDir, "${target.nameWithoutExtension}.hls.${if (playlist.isFragmentedMp4) "mp4" else "ts"}.tmp")
        try {
            var bytes = 0L
            temp.outputStream().buffered().use { out ->
                playlist.initSegmentUrl?.let { bytes = downloadSegment(client, it, headers, out, bytes, maxBytes) }
                playlist.segmentUrls.forEachIndexed { index, url ->
                    bytes = downloadSegment(client, url, headers, out, bytes, maxBytes)
                    onProgress(HlsProgress(index + 1, playlist.segmentUrls.size, bytes))
                }
            }
            currentCoroutineContext().ensureActive()
            target.delete()
            if (!exportAac(temp, target, metadata) || target.length() == 0L) throw HlsException.Export()
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            temp.delete()
        }
    }

private suspend fun fetchPlaylist(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
): HlsPlaylist {
    var lastError: IOException? = null
    repeat(HLS_ATTEMPTS) {
        currentCoroutineContext().ensureActive()
        try {
            client.newCall(request(url, headers)).execute().use { response ->
                if (!response.isSuccessful) throw HlsException.Http(response.code, url)
                return parseHlsPlaylist(response.body.string(), url)
            }
        } catch (error: IOException) {
            lastError = error
        }
    }
    throw lastError ?: IOException("Playlist download failed")
}

// Each attempt buffers the segment in memory so a failed attempt never leaves partial bytes in the output.
private suspend fun downloadSegment(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
    out: OutputStream,
    bytesSoFar: Long,
    maxBytes: Long,
): Long {
    var lastError: IOException? = null
    repeat(HLS_ATTEMPTS) {
        currentCoroutineContext().ensureActive()
        try {
            client.newCall(request(url, headers)).execute().use { response ->
                if (!response.isSuccessful) throw HlsException.Http(response.code, url)
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(32 * 1024)
                val stream = response.body.byteStream()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(chunk)
                    if (count < 0) break
                    buffer.write(chunk, 0, count)
                    if (bytesSoFar + buffer.size() > maxBytes) throw HlsException.SizeLimit(maxBytes)
                }
                buffer.writeTo(out)
                return bytesSoFar + buffer.size()
            }
        } catch (error: IOException) {
            lastError = error
        }
    }
    throw lastError ?: IOException("Segment download failed")
}

private fun request(
    url: String,
    headers: Map<String, String>,
): Request =
    Request
        .Builder()
        .url(url)
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()

private const val HLS_ATTEMPTS = 3
