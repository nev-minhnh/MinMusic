package com.metrolist.shared.repository

import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsMatch
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.MlxBuilder
import com.metrolist.shared.api.lyrics.MlxWord
import com.metrolist.shared.api.lyrics.lyricsTimingKind
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyricsSelectionTest {
    private class FakeFetcher(
        private val answers: Map<LyricsProvider, Pair<Long, String?>>,
        private val ownLyrics: String? = null,
    ) : LyricsFetcher {
        override suspend fun sourceLyrics(id: String): LyricsWithProvider? = ownLyrics?.let { LyricsWithProvider(it, "Zing MP3") }

        override suspend fun getLyrics(
            title: String,
            artist: String,
            duration: Long?,
            album: String?,
            videoId: String?,
            provider: LyricsProvider,
            isVideoContext: Boolean,
        ): LyricsWithProvider? {
            val (delayMs, lyrics) = answers[provider] ?: return null
            delay(delayMs)
            return lyrics?.let { LyricsWithProvider(it, providerName(provider)) }
        }

        private fun providerName(provider: LyricsProvider) =
            when (provider) {
                LyricsProvider.LRCLIB -> "LrcLib"
                LyricsProvider.KUGOU -> "KuGou"
                LyricsProvider.BETTERLYRICS -> "BetterLyrics"
                LyricsProvider.YOUTUBE -> "YouTube Music"
                LyricsProvider.SIMPMUSIC -> "SimpMusic"
                else -> provider.name
            }
    }

    private val song = listOf("Đêm nay ta say trong men tình", "Cho quên đi bao nhiêu muộn phiền", "Người ơi xin đừng xa", "Kẻ say tình mãi chờ")
    private val otherSong = listOf("Một ngày mưa rơi rất nhẹ", "Em đi qua phố vắng", "Lặng nhìn mây trôi", "Say tình một đời")

    private fun synced(
        source: String,
        lines: List<String>,
        title: String?,
        artist: String?,
        seconds: Long?,
    ): String {
        val builder = MlxBuilder().meta(source).song(title = "query", artist = "query")
        lines.forEachIndexed { index, text -> builder.line(15_000L + index * 50_000L, text) }
        return LyricsMatch.withMatchedSong(builder.build(), title, artist, seconds?.times(1000))
    }

    private fun wordSynced(lines: List<String>): String {
        val builder = MlxBuilder().meta("BetterLyrics").song(title = "query", artist = "query")
        lines.forEachIndexed { index, text ->
            val start = 15_000L + index * 50_000L
            builder.line(start, start + 4_000L, words = listOf(MlxWord(text, start, start + 4_000L)))
        }
        return builder.build()
    }

    private fun plain(
        source: String,
        lines: List<String>,
    ): String = MlxBuilder().meta(source).song().apply { lines.forEach(::unsyncedLine) }.build()

    private suspend fun lookup(
        answers: Map<LyricsProvider, Pair<Long, String?>>,
        videoId: String = "video",
        ownLyrics: String? = null,
    ): LyricsWithProvider? =
        LyricsRepository(FakeFetcher(answers, ownLyrics), LyricsTranslationClient(HttpClient(MockEngine { error("No request expected") })))
            .getLyrics(videoId = videoId, title = "Kẻ Say Tình | Official MV", artist = "Quốc Thiên", duration = 200_000L)

    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun aFastSyncedWrongSongLosesToTheRightSong() =
        runBlocking {
            val result =
                lookup(
                    mapOf(
                        LyricsProvider.KUGOU to (0L to synced("KuGou", otherSong, "Say Tình", "Quốc Thiên", 200)),
                        LyricsProvider.LRCLIB to (300L to synced("LrcLib", song, "Kẻ Say Tình", "Quốc Thiên", 201)),
                    ),
                )
            assertEquals("LrcLib", assertNotNull(result).provider)
        }

    @Test
    fun theSameAnswersAlwaysGiveTheSameLyrics() =
        runBlocking {
            val lrclib = synced("LrcLib", song, "Kẻ Say Tình", "Quốc Thiên", 201)
            val youtube = plain("YouTube Music", song)
            val kugou = synced("KuGou", otherSong, "Kẻ Say Tình (Remix)", "DJ Someone", 200)

            fun answers(
                lrclibDelay: Long,
                youtubeDelay: Long,
            ) = mapOf(
                LyricsProvider.LRCLIB to (lrclibDelay to lrclib),
                LyricsProvider.YOUTUBE to (youtubeDelay to youtube),
                LyricsProvider.KUGOU to (0L to kugou),
            )
            val first = lookup(answers(0, 400))
            val second = lookup(answers(400, 0))
            assertEquals("LrcLib", first?.provider)
            assertEquals(first, second)
        }

    @Test
    fun serverMatchedLyricsNeedAConfidentWitness() =
        runBlocking {
            assertNull(lookup(mapOf(LyricsProvider.BETTERLYRICS to (0L to wordSynced(otherSong)))))
            val result =
                lookup(
                    mapOf(
                        LyricsProvider.BETTERLYRICS to (0L to wordSynced(song)),
                        LyricsProvider.YOUTUBE to (200L to plain("YouTube Music", song)),
                    ),
                )
            assertEquals("BetterLyrics", result?.provider)
            assertEquals(LyricsTimingKind.WORD_SYNCED, lyricsTimingKind(LyricsUtils.parseLyrics(assertNotNull(result).lyrics)))
        }

    @Test
    fun videoCaptionsNeedTheSongsWordsToCount() =
        runBlocking {
            val captions = synced("YouTube Subtitle", listOf("[âm nhạc]", "anh biet la buon lam chu", "khi phai tim den con say"), null, null, null)
            assertNull(lookup(mapOf(LyricsProvider.YOUTUBESUBTITLE to (0L to captions))))
            val result =
                lookup(
                    mapOf(
                        LyricsProvider.YOUTUBESUBTITLE to (0L to synced("YouTube Subtitle", song, null, null, null)),
                        LyricsProvider.YOUTUBE to (100L to plain("YouTube Music", song)),
                    ),
                )
            assertEquals(LyricsTimingKind.LINE_SYNCED, lyricsTimingKind(LyricsUtils.parseLyrics(assertNotNull(result).lyrics)))
        }

    @Test
    fun timingForAnotherLengthIsShownAsPlainText() =
        runBlocking {
            val result = lookup(mapOf(LyricsProvider.LRCLIB to (0L to synced("LrcLib", song, "Kẻ Say Tình", "Quốc Thiên", 215))))
            val lines = LyricsUtils.parseLyrics(assertNotNull(result).lyrics)
            assertEquals(song, lines.map { it.text })
            assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(lines))
        }

    @Test
    fun aZingSongUsesItsOwnLyrics() =
        runBlocking {
            val result =
                lookup(
                    mapOf(LyricsProvider.KUGOU to (0L to synced("KuGou", otherSong, "Kẻ Say Tình 2", "Quốc Thiên", 200))),
                    videoId = "zing:ZW6AB12C",
                    ownLyrics = plain("Zing MP3", song),
                )
            assertEquals("Zing MP3", result?.provider)
            assertEquals(song, LyricsUtils.parseLyrics(assertNotNull(result).lyrics).map { it.text })
        }

    @Test
    fun noConfidentMatchShowsNoLyrics() =
        runBlocking {
            assertNull(lookup(mapOf(LyricsProvider.KUGOU to (0L to synced("KuGou", otherSong, "Say Tình", "Quốc Thiên", 200)))))
            assertNull(lookup(mapOf(LyricsProvider.LRCLIB to (0L to synced("LrcLib", song, "Kẻ Say Tình", "Đan Nguyên", 200)))))
        }
}
