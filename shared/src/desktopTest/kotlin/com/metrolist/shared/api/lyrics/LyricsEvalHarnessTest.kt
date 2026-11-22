package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.YouTubeSearchOperations
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.youtube.lyrics.InnerTubeLyricsApi
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.io.File
import java.text.Normalizer
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Real-network evaluation of automatic lyrics matching over real tracks (never runs in CI).
 *
 * Run with: mm-build.sh <worktree> :shared:desktopTest --tests '*LyricsEvalHarness*' -DrunLiveLyricsTests=true
 * The report is written to shared/build/lyrics-eval/report-<time>.md.
 *
 * Each case is looked up on YouTube Music (as the app gets it), then resolved by [LyricsRepository.getLyrics]
 * with the default provider chain, and every provider is also asked on its own. Correctness is checked against
 * lines known to be in the song (folded, diacritics-insensitive), or, when none are given, against YouTube
 * Music's own lyrics for the song's audio track.
 */
class LyricsEvalHarnessTest {
    private enum class Kind { SONG, VIDEO, FOREIGN }

    private data class Case(
        val label: String,
        val query: String,
        val kind: Kind = Kind.SONG,
        val phrases: List<String> = emptyList(),
        val expectNone: Boolean = false,
    )

    private val cases =
        listOf(
            Case("Shape of You", "Shape of You Ed Sheeran", phrases = listOf("the club isnt the best place to find a lover")),
            Case("Shape of You (MV)", "Ed Sheeran Shape of You Official Music Video", Kind.VIDEO, listOf("the club isnt the best place to find a lover")),
            Case("Blinding Lights", "Blinding Lights The Weeknd", phrases = listOf("ive been tryna call", "on my own for long enough")),
            Case("Someone Like You", "Someone Like You Adele", phrases = listOf("i heard that youre settled down")),
            Case("Hello (Adele)", "Hello Adele", phrases = listOf("hello its me", "wondering if after all these years")),
            Case("Hello (Lionel Richie)", "Hello Lionel Richie", phrases = listOf("alone with you inside my mind")),
            Case("Stay (Kid LAROI)", "Stay The Kid LAROI Justin Bieber", phrases = listOf("i do the same thing i told you that i never would")),
            Case("Faded", "Faded Alan Walker", phrases = listOf("you were the shadow to my light")),
            Case("Despacito (Remix)", "Despacito Remix Luis Fonsi Daddy Yankee Justin Bieber", phrases = listOf("comin over in my direction")),
            Case("bad guy", "bad guy Billie Eilish", phrases = listOf("white shirt now red", "bruises on both my knees")),
            Case("Closer", "Closer The Chainsmokers Halsey", phrases = listOf("i was doing just fine before i met you")),
            Case("Dynamite (BTS)", "Dynamite BTS", phrases = listOf("in the stars tonight")),
            Case("How You Like That", "How You Like That BLACKPINK", phrases = listOf("look at you now look at me", "blackpink in your area")),
            Case("Ditto (NewJeans)", "Ditto NewJeans", phrases = listOf("stay in the middle")),
            Case("Lemon (Yonezu)", "Lemon 米津玄師", phrases = listOf("夢ならばどれほどよかったでしょう")),
            Case("Idol (YOASOBI)", "アイドル YOASOBI", phrases = listOf("無敵の笑顔で荒らすメディア")),
            Case("Lạc Trôi", "Lạc Trôi Sơn Tùng M-TP", phrases = listOf("nguoi theo huong hoa may mu giang loi")),
            Case("Lạc Trôi (MV)", "Lạc Trôi Official Music Video Sơn Tùng M-TP", Kind.VIDEO, listOf("nguoi theo huong hoa may mu giang loi")),
            Case("Hãy Trao Cho Anh", "Hãy Trao Cho Anh Sơn Tùng M-TP Snoop Dogg", phrases = listOf("bong ai do nhe nhang vut qua noi day")),
            Case("Chúng Ta Của Hiện Tại", "Chúng Ta Của Hiện Tại Sơn Tùng M-TP", phrases = listOf("mua thu mang giac mo quay ve")),
            Case("Nơi Này Có Anh", "Nơi Này Có Anh Sơn Tùng M-TP", phrases = listOf("em la ai tu dau buoc den noi day")),
            Case("Em Của Ngày Hôm Qua", "Em Của Ngày Hôm Qua Sơn Tùng M-TP", phrases = listOf("chia tay trong em co quen duoc")),
            Case("Để Mị Nói Cho Mà Nghe", "Để Mị Nói Cho Mà Nghe Hoàng Thùy Linh"),
            Case("See Tình", "See Tình Hoàng Thùy Linh"),
            Case("Mang Tiền Về Cho Mẹ", "Mang Tiền Về Cho Mẹ Đen Nguyên Thảo"),
            Case("Bài Này Chill Phết", "Bài Này Chill Phết Đen MIN"),
            Case("Không Môn Đăng Hộ Đối", "Không Môn Đăng Hộ Đối Nguyễn Vĩ"),
            Case("Kẻ Say Tình (MUST)", "Kẻ Say Tình Quốc Thiên"),
            Case("Kẻ Say Tình (Zing id)", "Kẻ Say Tình Quốc Thiên", Kind.FOREIGN),
            Case("Hoa Nở Không Màu", "Hoa Nở Không Màu Hoài Lâm"),
            Case("Em Gái Mưa", "Em Gái Mưa Hương Tràm"),
            Case("Có Chàng Trai Viết Lên Cây", "Có Chàng Trai Viết Lên Cây Phan Mạnh Quỳnh"),
            Case("Ánh Nắng Của Anh", "Ánh Nắng Của Anh Đức Phúc"),
            Case("Anh Đã Quen Với Cô Đơn", "Anh Đã Quen Với Cô Đơn Soobin Hoàng Sơn"),
            Case("Người Lạ Ơi", "Người Lạ Ơi Superbrothers Karik Orange"),
            Case("Không Môn Đăng Hộ Đối (NCT id)", "Không Môn Đăng Hộ Đối Nguyễn Vĩ", Kind.FOREIGN),
            Case("Clair de Lune (instrumental)", "Clair de Lune Debussy", expectNone = true),
        )

    private data class Track(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long?,
        val album: String?,
        val isVideo: Boolean,
    )

    @Test
    fun evaluateAutomaticLyricsMatching() =
        runBlocking {
            assumeTrue(System.getProperty("runLiveLyricsTests") == "true")
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val http = GlobalYouTube.client
            val api = InnerTubeLyricsApi(GlobalYouTube.innerTube)
            val client = LyricsClient(http, api, lyprioLyricsProvider = null)
            val repository = LyricsRepository(client, LyricsTranslationClient(http), databaseDao = null, youtubeLyricsApi = api)
            val search = YouTubeSearchOperations(GlobalYouTube.innerTube)
            val youtubeLyrics = YouTubeLyricsProvider(api)
            val providers =
                listOf(
                    LyricsProvider.BETTERLYRICS,
                    LyricsProvider.LRCRED,
                    LyricsProvider.UNISON,
                    LyricsProvider.LRCLIB,
                    LyricsProvider.KUGOU,
                    LyricsProvider.PAXSENIX,
                    LyricsProvider.SIMPMUSIC,
                    LyricsProvider.YOUTUBESUBTITLE,
                    LyricsProvider.YOUTUBE,
                )
            val rows = mutableListOf<String>()
            val details = StringBuilder()
            var wrong = 0
            var correct = 0
            var none = 0
            var unsure = 0
            var synced = 0
            for (case in cases) {
                val track = resolveTrack(search, case)
                if (track == null) {
                    rows += "| ${case.label} | (search failed) | | | | | | |"
                    continue
                }
                val reference =
                    if (case.phrases.isEmpty() && !case.expectNone) {
                        // YouTube Music's own lyrics for the audio track are the ground truth when no line is given.
                        val audioId = if (case.kind == Kind.SONG) track.videoId else resolveTrack(search, case.copy(kind = Kind.SONG))?.videoId
                        audioId?.let { id -> runCatching { youtubeLyrics.getLyrics(id) }.getOrNull() }?.let(::plainText)
                    } else {
                        null
                    }
                val lookupId = if (case.kind == Kind.FOREIGN) "zing:EVAL${case.label.hashCode()}" else track.videoId
                val started = System.currentTimeMillis()
                val chosen =
                    withTimeoutOrNull(60.seconds) {
                        runCatching {
                            repository.getLyrics(
                                videoId = lookupId,
                                title = track.title,
                                artist = track.artist,
                                duration = track.durationMs,
                                album = track.album,
                                isVideoContext = track.isVideo,
                            )
                        }.getOrNull()
                    }
                val elapsed = System.currentTimeMillis() - started
                val verdict = verdict(case, reference, chosen?.lyrics)
                when (verdict) {
                    "WRONG" -> wrong++
                    "OK" -> correct++
                    "NONE", "OK-NONE" -> none++
                    else -> unsure++
                }
                val doc = chosen?.lyrics?.let(LyricsUtils::parseMlxDocument)
                val lines = doc?.lines.orEmpty()
                val timing = lyricsTimingKind(lines)
                if (timing != LyricsTimingKind.UNSYNCED && verdict == "OK") synced++
                val lastMs = lines.filter { it.time >= 0 }.maxOfOrNull { it.time }
                val firstMs = lines.firstOrNull { it.time >= 0 && it.text.isNotBlank() }?.time
                val candDur = doc?.song?.durationMs
                rows +=
                    "| ${case.label} | ${track.title.take(40)} / ${track.artist.take(25)} / ${track.durationMs?.div(1000)}s | " +
                    "${chosen?.provider ?: "-"} | ${timing.name.lowercase()} | " +
                    "${if (timing != LyricsTimingKind.UNSYNCED) "${firstMs?.div(1000)}s..${lastMs?.div(1000)}s" else "-"} | " +
                    "${candDur?.let { d -> track.durationMs?.let { "${(d - it) / 1000}s" } } ?: "-"} | " +
                    "${doc?.song?.title?.take(30) ?: "-"} / ${doc?.song?.artist?.take(20) ?: "-"} | **$verdict** | ${elapsed / 1000}s |"

                details.appendLine("### ${case.label}  (id=$lookupId, video=${track.isVideo})")
                details.appendLine("chosen: ${chosen?.provider} -> ${firstLines(chosen?.lyrics)}")
                val perProvider =
                    coroutineScope {
                        providers.map { provider ->
                            async {
                                val result =
                                    withTimeoutOrNull(25.seconds) {
                                        runCatching {
                                            client.getLyrics(track.title, track.artist, track.durationMs, track.album, lookupId, provider, track.isVideo)
                                        }.getOrNull()
                                    }
                                provider to result
                            }
                        }.awaitAll()
                    }
                for ((provider, result) in perProvider) {
                    val pDoc = result?.lyrics?.let(LyricsUtils::parseMlxDocument)
                    val pTiming = pDoc?.lines?.let(::lyricsTimingKind)
                    details.appendLine(
                        "- $provider: ${if (result == null) "none" else verdict(case, reference, result.lyrics)} " +
                            "${pTiming?.name?.lowercase().orEmpty()} meta=[${pDoc?.song?.title} / ${pDoc?.song?.artist} / ${pDoc?.song?.durationMs}] " +
                            firstLines(result?.lyrics),
                    )
                }
                details.appendLine()
            }
            val report =
                buildString {
                    appendLine("# Lyrics evaluation")
                    appendLine()
                    appendLine("cases=${cases.size} ok=$correct wrong=$wrong none=$none unsure=$unsure synced-ok=$synced")
                    appendLine()
                    appendLine("| case | input (title / artist / duration) | chosen | timing | first..last | dur delta | matched meta | verdict | time |")
                    appendLine("|---|---|---|---|---|---|---|---|---|")
                    rows.forEach(::appendLine)
                    appendLine()
                    append(details)
                }
            val dir = File("build/lyrics-eval").apply { mkdirs() }
            File(dir, "report-${System.currentTimeMillis()}.md").writeText(report)
            println(report)
        }

    private suspend fun resolveTrack(
        search: YouTubeSearchOperations,
        case: Case,
    ): Track? {
        val items = search.search(case.query, recordSearchHistory = false).orEmpty().filterIsInstance<SongItem>()
        val wantVideo = case.kind == Kind.VIDEO
        val song =
            items.firstOrNull { (it.musicVideoType == MUSIC_VIDEO_TYPE_OMV || it.musicVideoType?.contains("UGC") == true) == wantVideo }
                ?: items.firstOrNull()
                ?: return null
        val duration = song.duration?.let { if (it < 100_000L) it * 1000L else it }
        return Track(song.id, song.title, song.artists.joinToString { it.name }, duration, song.album?.name, wantVideo)
    }

    private fun verdict(
        case: Case,
        reference: String?,
        lyrics: String?,
    ): String {
        if (lyrics.isNullOrBlank()) return if (case.expectNone) "OK-NONE" else "NONE"
        if (case.expectNone) return "WRONG"
        val text = fold(plainText(lyrics))
        if (case.phrases.isNotEmpty()) {
            return if (case.phrases.any { text.replace(" ", "").contains(fold(it).replace(" ", "")) }) "OK" else "WRONG"
        }
        reference ?: return "UNSURE"
        val a = fold(reference).split(' ').filter { it.isNotBlank() }.toSet()
        val b = text.split(' ').filter { it.isNotBlank() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return "UNSURE"
        val jaccard = a.intersect(b).size.toDouble() / a.union(b).size
        return when {
            jaccard >= 0.3 -> "OK"
            jaccard < 0.15 -> "WRONG"
            else -> "UNSURE(${"%.2f".format(jaccard)})"
        }
    }

    private fun plainText(lyrics: String): String = LyricsUtils.parseLyrics(lyrics).joinToString(" ") { it.text }

    private fun firstLines(lyrics: String?): String =
        lyrics
            ?.let(LyricsUtils::parseLyrics)
            ?.filter { it.text.isNotBlank() }
            ?.take(2)
            ?.joinToString(" / ") { it.text.take(50) }
            ?.let { "\"$it\"" }
            .orEmpty()

    private fun fold(value: String): String =
        Normalizer
            .normalize(value.lowercase().replace('đ', 'd'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
