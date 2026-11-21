package com.metrolist.shared.source.soundcloud

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.services.soundcloud.SoundcloudParsingHelper
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/**
 * Self-contained SoundCloud client on top of NewPipeExtractor.
 *
 * NewPipeExtractor scrapes the public web `client_id` and resolves streams (policy checks, DRM
 * filtering, signed transcoding URLs). Listing endpoints are mapped here because the extractor's
 * list items do not expose the numeric track ids the app needs. Only public api-v2 content
 * endpoints are called; no ad, tracking or event endpoints.
 *
 * All functions run on [ioDispatcher] and throw [SoundCloudException] for content and parsing
 * failures or [IOException] for transport failures.
 */
class SoundCloudClient(
    private val initialize: () -> Unit = { NewPipeSetup.ensureInitialized() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun search(
        query: String,
        filter: SoundCloudSearchFilter,
        pageToken: String? = null,
    ): SoundCloudPage<SoundCloudSearchItem> =
        io {
            val url =
                pageToken?.let(::checkedPageUrl)
                    ?: "${API_URL}search/${filter.pathSegment}?q=${encode(query)}&limit=$PAGE_SIZE&offset=0"
            SoundCloudJsonMapper.searchPage(apiGet(url), filter)
        }

    /** Accepts `sc:<id>`, a bare numeric id, or a soundcloud.com / on.soundcloud.com track URL. */
    suspend fun track(urlOrId: String): SoundCloudTrack =
        io {
            val obj = resolve(urlOrId, "tracks")
            if (SoundCloudJsonMapper.kind(obj) != "track") {
                throw SoundCloudException.Unavailable("SoundCloud link is not a track")
            }
            SoundCloudJsonMapper.track(obj)
                ?: throw SoundCloudException.ExtractionFailed("SoundCloud track is missing required fields")
        }

    /** Accepts a playlist or album URL, or its numeric id. Returns every track in playlist order. */
    suspend fun playlist(urlOrId: String): SoundCloudPlaylist =
        io {
            val obj = resolve(urlOrId, "playlists")
            if (SoundCloudJsonMapper.kind(obj) != "playlist") {
                throw SoundCloudException.Unavailable("SoundCloud link is not a playlist")
            }
            val summary =
                SoundCloudJsonMapper.playlistSummary(obj)
                    ?: throw SoundCloudException.ExtractionFailed("SoundCloud playlist is missing required fields")
            val (ids, known) = SoundCloudJsonMapper.playlistEntries(obj)
            val fetched =
                ids
                    .filterNot(known::containsKey)
                    .chunked(TRACKS_PER_LOOKUP)
                    .flatMap { chunk ->
                        SoundCloudJsonMapper
                            .parseArray(apiGet("${API_URL}tracks?ids=${chunk.joinToString(",")}"))
                            .mapNotNull { (it as? JsonObject)?.let(SoundCloudJsonMapper::track) }
                    }.associateBy { it.numericId }
            val all = known + fetched
            SoundCloudPlaylist(summary, ids.mapNotNull(all::get))
        }

    /** Global "trending" chart. SoundCloud rejects the region parameter, so no regional charts. */
    suspend fun charts(pageToken: String? = null): SoundCloudPage<SoundCloudTrack> =
        io {
            val url =
                pageToken?.let(::checkedPageUrl)
                    ?: "${API_URL}charts?kind=trending&genre=${encode(CHART_GENRE)}&limit=$PAGE_SIZE&offset=0"
            SoundCloudJsonMapper.chartsPage(apiGet(url))
        }

    /**
     * Resolves the best playable audio stream. Go+ previews ([SoundCloudException.PreviewOnly]) and
     * region blocks ([SoundCloudException.Blocked]) are never worked around, and DRM-encrypted
     * transcodings are skipped. The result is also the source for downloads (owner decision
     * 2026-11-21). Resolve again once [SoundCloudStream.expiresAtMs] has passed or on HTTP 403.
     */
    suspend fun stream(urlOrId: String): SoundCloudStream =
        io {
            val id = numericIdOf(urlOrId)
            val url = if (id != null) "$TRACK_API_URL$id" else canonicalUrl(urlOrId)
            val extractor = ServiceList.SoundCloud.getStreamExtractor(LinkHandler(url, url, id?.toString() ?: url))
            val audioStreams =
                try {
                    extractor.fetchPage()
                    extractor.audioStreams
                } catch (e: SoundCloudGoPlusContentException) {
                    throw SoundCloudException.PreviewOnly(e)
                } catch (e: GeographicRestrictionException) {
                    throw SoundCloudException.Blocked(e)
                } catch (e: ContentNotAvailableException) {
                    throw SoundCloudException.Unavailable(e.message ?: "SoundCloud track is unavailable", e)
                }
            val candidates =
                audioStreams
                    .filter { it.isUrl }
                    .map { SoundCloudStreamCandidate(it.id, it.content, it.deliveryMethod == DeliveryMethod.HLS) }
            SoundCloudStreamSelector.select(candidates, clock()) ?: throw SoundCloudException.NoPlayableStream()
        }

    private suspend fun <T> io(block: () -> T): T =
        withContext(ioDispatcher) {
            initialize()
            try {
                block()
            } catch (e: ExtractionException) {
                throw SoundCloudException.ExtractionFailed(e.message ?: "SoundCloud extraction failed", e)
            } catch (e: SerializationException) {
                throw SoundCloudException.ExtractionFailed("Unexpected SoundCloud response", e)
            }
        }

    private fun resolve(
        urlOrId: String,
        collection: String,
    ): JsonObject {
        val id = numericIdOf(urlOrId)
        val url =
            if (id != null) {
                "$API_URL$collection/$id"
            } else {
                "${API_URL}resolve?url=${encode(canonicalUrl(urlOrId))}"
            }
        return SoundCloudJsonMapper.parseObject(apiGet(url))
    }

    private fun apiGet(url: String): String {
        val separator = if ('?' in url) '&' else '?'
        val response =
            NewPipe
                .getDownloader()
                .get("$url${separator}client_id=${encode(SoundcloudParsingHelper.clientId())}", NewPipe.getPreferredLocalization())
        return when (val code = response.responseCode()) {
            in 200..299 -> response.responseBody()
            404 -> throw SoundCloudException.Unavailable("SoundCloud content not found")
            else -> throw IOException("SoundCloud API returned HTTP $code")
        }
    }

    /**
     * Normalizes to `https://soundcloud.com/<path>`, following on.soundcloud.com short links and
     * dropping tracking query parameters.
     */
    private fun canonicalUrl(
        url: String,
        followShortLink: Boolean = true,
    ): String {
        val uri =
            runCatching { URI(url.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("Not a SoundCloud URL")
        require(uri.scheme == "https" || uri.scheme == "http") { "Not a SoundCloud URL" }
        return when (uri.host?.lowercase()) {
            "soundcloud.com", "www.soundcloud.com", "m.soundcloud.com" -> {
                val path = uri.rawPath.orEmpty().trimEnd('/')
                require(path.isNotEmpty()) { "Not a SoundCloud content URL" }
                "https://soundcloud.com$path"
            }
            "on.soundcloud.com" -> {
                require(followShortLink) { "Short link did not resolve to a SoundCloud URL" }
                canonicalUrl(NewPipe.getDownloader().head(url.trim()).latestUrl(), followShortLink = false)
            }
            else -> throw IllegalArgumentException("Not a SoundCloud URL")
        }
    }

    private fun checkedPageUrl(token: String): String {
        require(token.startsWith(API_URL)) { "Page token does not belong to SoundCloud" }
        return token
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal companion object {
        const val API_URL = "https://api-v2.soundcloud.com/"
        private const val TRACK_API_URL = "https://api.soundcloud.com/tracks/"
        private const val CHART_GENRE = "soundcloud:genres:all-music"
        private const val PAGE_SIZE = 20

        // api-v2 accepts at most 50 ids per tracks lookup.
        private const val TRACKS_PER_LOOKUP = 50
        private val apiTrackUrl = Regex("^https?://api(?:-v2)?\\.soundcloud\\.com/tracks/(\\d+)(?:[/?#].*)?$")

        fun numericIdOf(urlOrId: String): Long? {
            val value = urlOrId.trim().removePrefix(SOUNDCLOUD_ID_PREFIX)
            if (value.isNotEmpty() && value.all(Char::isDigit)) return value.toLongOrNull()
            return apiTrackUrl.find(value)?.groupValues?.get(1)?.toLongOrNull()
        }
    }
}
