/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.network.QuietHttpFailures
import com.metrolist.shared.repository.usableProviderLyrics
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceCapability
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import com.metrolist.shared.source.isForeignSourceId
import com.metrolist.shared.source.sourceOf
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

interface LyricsFetcher {
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
        provider: LyricsProvider = LyricsProvider.AUTO,
        isVideoContext: Boolean = false,
    ): LyricsWithProvider?

    suspend fun getLyricsResults(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
        provider: LyricsProvider = LyricsProvider.AUTO,
        isVideoContext: Boolean = false,
    ): List<LyricsWithProvider> = listOfNotNull(getLyrics(title, artist, duration, album, videoId, provider, isVideoContext))

    /** False for a provider this build cannot use (Lyprio without its endpoint/key), so lookups skip it at once. */
    fun isProviderAvailable(provider: LyricsProvider): Boolean = true

    /** The song's own lyrics from its source (Zing MP3, NhacCuaTui) for ids of those sources, else null. */
    suspend fun sourceLyrics(id: String): LyricsWithProvider? = null
}

class LyricsClient(
    httpClient: HttpClient,
    youtubeLyricsApi: YouTubeLyricsApi,
    private val lyprioLyricsProvider: LyprioLyricsProvider?,
    private val sourceRegistry: SourceRegistry? = null,
) : LyricsFetcher {
    private val betterLyricsProvider = BetterLyricsProvider(httpClient)
    private val unisonLyricsProvider = UnisonProvider(httpClient)
    private val lrcLibProvider = LrcLibLyricsProvider(httpClient)
    private val lrcredLyricsProvider = LrcredLyricsProvider(httpClient)
    private val simpMusicProvider = SimpMusicLyricsProvider(httpClient)
    private val kuGouLyricsProvider = KuGouLyricsProvider(httpClient)
    private val paxsenixLyricsProvider = PaxsenixLyricsProvider(httpClient)
    private val youtubeLyricsProvider = YouTubeLyricsProvider(youtubeLyricsApi)
    private val youtubeSubtitleLyricsProvider = YouTubeSubtitleLyricsProvider(youtubeLyricsApi)

    override fun isProviderAvailable(provider: LyricsProvider): Boolean = provider != LyricsProvider.LYPRIO || lyprioLyricsProvider != null

    // Lyrics sources fail for many outside reasons; their errors and timeouts are not worth logging.
    override suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): LyricsWithProvider? =
        withContext(QuietHttpFailures) { fetchLyrics(title, artist, duration, album, videoId, provider, isVideoContext) }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): LyricsWithProvider? {
        val durationMs = duration ?: 0L
        val durationSec = durationMs / 1000
        // Ids of other sources mean nothing to YouTube; title-search providers still run for them.
        val youTubeVideoId = videoId?.takeUnless(::isForeignSourceId)
        return when (provider) {
            LyricsProvider.USER -> {
                null
            }

            LyricsProvider.LYPRIO -> {
                val lyprio = checkNotNull(lyprioLyricsProvider) { "Lyprio is not configured" }
                lyprio.getLyrics(title, artist, duration, album, youTubeVideoId)
            }

            LyricsProvider.BETTERLYRICS -> {
                result("BetterLyrics") { betterLyricsProvider.getLyrics(title, artist, duration, album) }
            }

            LyricsProvider.LRCRED -> {
                result("lrc.red") { lrcredLyricsProvider.getLyrics(title, artist, duration, album, youTubeVideoId) }
            }

            LyricsProvider.UNISON -> {
                result("Unison") { unisonLyricsProvider.getLyrics(title, artist, duration, album, youTubeVideoId, isVideoContext) }
            }

            LyricsProvider.SIMPMUSIC -> {
                result("SimpMusic") { youTubeVideoId?.let { simpMusicProvider.getLyrics(it, duration) } }
            }

            LyricsProvider.LRCLIB -> {
                result("LrcLib") { lrcLibProvider.getLyrics(title, artist, duration, album) }
            }

            LyricsProvider.KUGOU -> {
                result("KuGou") { kuGouLyricsProvider.getLyrics(title, artist, durationSec.toInt(), album) }
            }

            LyricsProvider.PAXSENIX -> {
                result("Paxsenix") { paxsenixLyricsProvider.getLyrics(title, artist, duration, album) }
            }

            LyricsProvider.YOUTUBESUBTITLE -> {
                result("YouTube Subtitle") { youTubeVideoId?.let { youtubeSubtitleLyricsProvider.getLyricsOrNull(it) } }
            }

            LyricsProvider.YOUTUBE -> {
                result("YouTube Music") { youTubeVideoId?.let { youtubeLyricsProvider.getLyrics(it) } }
            }

            LyricsProvider.AUTO -> {
                // A song's own source has synced lyrics for exactly this recording, so it goes first.
                if (videoId != null && isForeignSourceId(videoId)) sourceLyrics(videoId)?.let { return it }
                var failure: Exception? = null
                var lyrics: LyricsWithProvider? = null
                val providers =
                    LyricsProvider.preferredProvidersForFetch(
                        LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder),
                        LyricsProvider.parseEnabledFromStoredCsv(Settings.shared.lyricsEnabledProviders),
                    )
                for (candidate in providers.filter { isProviderAvailable(it) }) {
                    try {
                        for (result in listOfNotNull(getLyrics(title, artist, duration, album, videoId, candidate, isVideoContext))) {
                            val parsed = LyricsUtils.parseLyrics(result.lyrics)
                            if (usableProviderLyrics(parsed) && (lyrics == null || LyricsUtils.hasPlaybackTiming(parsed))) lyrics = result
                        }
                        if (lyrics?.let { LyricsUtils.hasPlaybackTiming(LyricsUtils.parseLyrics(it.lyrics)) } == true) break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failure = error
                    }
                }
                if (lyrics == null) failure?.let { throw it }
                lyrics
            }
        }
    }

    override suspend fun getLyricsResults(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        provider: LyricsProvider,
        isVideoContext: Boolean,
    ): List<LyricsWithProvider> =
        withContext(QuietHttpFailures) {
            if (provider == LyricsProvider.LYPRIO) {
                val lyprio = checkNotNull(lyprioLyricsProvider) { "Lyprio is not configured" }
                lyprio.getLyricsResults(title, artist, duration, album, videoId).map { LyricsWithProvider(it, "Lyprio") }
            } else {
                listOfNotNull(fetchLyrics(title, artist, duration, album, videoId, provider, isVideoContext))
            }
        }

    override suspend fun sourceLyrics(id: String): LyricsWithProvider? {
        val source = sourceRegistry?.available(sourceOf(id))?.takeIf { SourceCapability.LYRICS in it.capabilities } ?: return null
        val lyrics =
            try {
                source.lyrics(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        val name =
            when (source.id) {
                SourceId.ZING -> "Zing MP3"
                SourceId.NCT -> "NhacCuaTui"
                else -> return null
            }
        return lyrics?.takeIf(String::isNotBlank)?.let { LyricsWithProvider(it, name) }
    }

    private suspend fun result(
        provider: String,
        fetch: suspend () -> String?,
    ): LyricsWithProvider? = fetch()?.let { LyricsWithProvider(it, provider) }
}
