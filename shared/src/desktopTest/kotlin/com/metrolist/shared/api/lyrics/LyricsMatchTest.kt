package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.lyrics.LyricsMatchConfidence.CONFIDENT
import com.metrolist.shared.api.lyrics.LyricsMatchConfidence.PLAUSIBLE
import com.metrolist.shared.api.lyrics.LyricsMatchConfidence.REJECTED
import com.metrolist.shared.model.LyricsEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LyricsMatchTest {
    private fun query(
        title: String,
        artist: String,
        seconds: Long?,
        isVideo: Boolean = false,
    ) = LyricsQuery(title, artist, seconds?.times(1000), isVideo = isVideo)

    private fun candidate(
        title: String,
        artist: String,
        seconds: Long?,
    ) = LyricsCandidateInfo(title, artist, seconds?.times(1000))

    @Test
    fun foldsVietnameseAndLatinDiacritics() {
        assertEquals("ke say tinh", LyricsMatch.normalize("Kẻ Say Tình"))
        assertEquals("son tung m tp", LyricsMatch.normalize("Sơn Tùng M-TP"))
        assertEquals("den vau", LyricsMatch.normalize("Đen Vâu"))
        assertEquals("hoang thuy linh", LyricsMatch.normalize("HOÀNG THÙY LINH"))
        assertEquals("beyonce", LyricsMatch.normalize("Beyoncé"))
        // Decomposed (NFD) input folds the same way.
        assertEquals("tinh", LyricsMatch.normalize("Tình"))
        assertEquals("dont stop me now", LyricsMatch.normalize("Don't Stop Me Now"))
    }

    @Test
    fun cleansUploadTitles() {
        assertEquals("lac troi", LyricsMatch.coreTitle("Lạc Trôi | Official Music Video | Sơn Tùng M-TP", "Sơn Tùng M-TP"))
        assertEquals("shape of you", LyricsMatch.coreTitle("Ed Sheeran - Shape of You (Official Music Video)", "Ed Sheeran"))
        assertEquals("khong mon dang ho doi", LyricsMatch.coreTitle("KHÔNG MÔN ĐĂNG HỘ ĐỐI - NGUYỄN VĨ | OFFICIAL MV", "Nguyễn Vĩ"))
        assertEquals("stay", LyricsMatch.coreTitle("Stay (feat. Justin Bieber)", "The Kid LAROI"))
        assertEquals("hay trao cho anh", LyricsMatch.coreTitle("Hãy Trao Cho Anh ft. Snoop Dogg", "Sơn Tùng M-TP"))
        assertEquals("Kẻ Say Tình (Remix)", LyricsMatch.searchTitle("Kẻ Say Tình (Remix) | Official MV", "Quốc Thiên"))
        assertEquals("Lạc Trôi", LyricsMatch.searchTitle("Lạc Trôi (Official Music Video)", "Sơn Tùng M-TP"))
        assertEquals("Sơn Tùng M-TP", LyricsMatch.searchArtist("Sơn Tùng M-TP Official"))
        assertEquals("Quốc Thiên", LyricsMatch.searchArtist("Quốc Thiên - Topic"))
    }

    @Test
    fun detectsVersionsOnlyWhereTheyMeanAVersion() {
        assertEquals(setOf("remix"), LyricsMatch.versionTags("Kẻ Say Tình (Vinahouse)"))
        assertEquals(setOf("live"), LyricsMatch.versionTags("Hello - Live at the BBC"))
        assertEquals(emptySet(), LyricsMatch.versionTags("Live Forever"))
        assertEquals(emptySet(), LyricsMatch.versionTags("Beat It"))
        assertEquals(setOf("instrumental"), LyricsMatch.versionTags("Nơi Này Có Anh (Beat)"))
        assertEquals(setOf("karaoke"), LyricsMatch.versionTags("Nơi Này Có Anh Karaoke"))
    }

    @Test
    fun acceptsTheSameSongWithDifferentSpelling() {
        val verdict = LyricsMatch.evaluate(query("Kẻ Say Tình", "Quốc Thiên", 280), candidate("Ke Say Tinh", "Quoc Thien", 281))
        assertEquals(CONFIDENT, verdict.confidence)
        assertTrue(verdict.allowSynced)
    }

    @Test
    fun rejectsAnotherSongSharingCommonWords() {
        assertEquals(REJECTED, LyricsMatch.evaluate(query("Kẻ Say Tình", "Quốc Thiên", 280), candidate("Say Tình", "Quốc Thiên", 279)).confidence)
        assertEquals(REJECTED, LyricsMatch.evaluate(query("Em Của Ngày Hôm Qua", "Sơn Tùng M-TP", 226), candidate("Em", "Sơn Tùng M-TP", 226)).confidence)
    }

    @Test
    fun aNumberedSequelIsAnotherSong() {
        // lrc.red answered "Kẻ Say Tình 2" for "Kẻ Say Tình" in 1.0.2.
        assertEquals(REJECTED, LyricsMatch.evaluate(query("Kẻ Say Tình", "Quốc Thiên", 308), candidate("Kẻ Say Tình 2", "Quốc Thiên", 309)).confidence)
        assertEquals(CONFIDENT, LyricsMatch.evaluate(query("22", "Taylor Swift", 232), candidate("22", "Taylor Swift", 231)).confidence)
        assertEquals(CONFIDENT, LyricsMatch.evaluate(query("Kẻ Say Tình - SKYNote 2025", "Quốc Thiên", 308), candidate("Kẻ Say Tình", "Quốc Thiên", 308)).confidence)
    }

    @Test
    fun translationsAndMachineLyricsAreNotTheSongsLyrics() {
        fun lines(vararg text: String) = text.map { LyricsEntry(-1, it) }
        assertTrue(LyricsMatch.textFits("Kẻ Say Tình", lines("Anh biết là buồn lắm chứ", "khi phải tìm đến cơn say")))
        assertFalse(LyricsMatch.textFits("Hãy Trao Cho Anh", lines("ظل شخص ما مر بهدوء وخفة", "سحرة فتنة جعل نشوة قلبي")))
        assertFalse(LyricsMatch.textFits("Chúng Ta Của Hiện Tại", lines("秋天把梦带回来", "还完好如初")))
        assertTrue(LyricsMatch.textFits("Lemon", lines("夢ならばどれほどよかったでしょう")))
        assertFalse(LyricsMatch.textFits("Lemon", lines("Las letras a continuación son generadas por AI", "imenava yo")))
    }

    @Test
    fun rejectsTheSameTitleByAnotherArtist() {
        val verdict = LyricsMatch.evaluate(query("Hello", "Lionel Richie", 248), candidate("Hello", "Adele", 295))
        assertEquals(REJECTED, verdict.confidence)
        assertEquals(REJECTED, LyricsMatch.evaluate(query("Hello", "Lionel Richie", 248), candidate("Hello", "Adele", 248)).confidence)
    }

    @Test
    fun acceptsFeaturedAndChannelArtists() {
        val verdict =
            LyricsMatch.evaluate(
                query("Stay", "The Kid LAROI, Justin Bieber", 141),
                candidate("STAY (with Justin Bieber)", "The Kid LAROI & Justin Bieber", 141),
            )
        assertEquals(CONFIDENT, verdict.confidence)
        assertEquals(
            CONFIDENT,
            LyricsMatch.evaluate(query("Lạc Trôi", "Sơn Tùng M-TP Official", 273), candidate("Lạc Trôi", "Sơn Tùng M-TP", 273)).confidence,
        )
    }

    @Test
    fun durationDecidesBetweenSyncedPlainAndRejected() {
        val close = LyricsMatch.evaluate(query("Faded", "Alan Walker", 212), candidate("Faded", "Alan Walker", 214))
        assertTrue(close.allowSynced)
        val edit = LyricsMatch.evaluate(query("Faded", "Alan Walker", 212), candidate("Faded", "Alan Walker", 222))
        assertEquals(CONFIDENT, edit.confidence)
        assertFalse(edit.allowSynced)
        assertEquals(REJECTED, LyricsMatch.evaluate(query("Faded", "Alan Walker", 212), candidate("Faded", "Alan Walker", 300)).confidence)
        // A music video runs longer than the song: its text is right, the song's timing is not.
        val video = LyricsMatch.evaluate(query("Shape of You (Official Music Video)", "Ed Sheeran", 263, isVideo = true), candidate("Shape of You", "Ed Sheeran", 233))
        assertEquals(CONFIDENT, video.confidence)
        assertFalse(video.allowSynced)
    }

    @Test
    fun anotherVersionKeepsTheWordsButNotTheTiming() {
        val remix = LyricsMatch.evaluate(query("Kẻ Say Tình (Remix)", "Quốc Thiên", 240), candidate("Kẻ Say Tình", "Quốc Thiên", 240))
        assertEquals(PLAUSIBLE, remix.confidence)
        assertFalse(remix.allowSynced)
    }

    @Test
    fun instrumentalsHaveNoLyrics() {
        assertEquals(REJECTED, LyricsMatch.evaluate(query("Nơi Này Có Anh (Beat)", "Sơn Tùng M-TP", 260), candidate("Nơi Này Có Anh", "Sơn Tùng M-TP", 260)).confidence)
        assertEquals(REJECTED, LyricsMatch.evaluate(query("Faded", "Alan Walker", 212), candidate("Faded (Instrumental)", "Alan Walker", 212)).confidence)
        // KuGou lists short clips and slowed or live cuts under the song's own name.
        assertEquals(REJECTED, LyricsMatch.evaluate(query("See Tình", "Hoàng Thùy Linh", 185), candidate("See Tình (9秒片段)", "Hoàng Thùy Linh", 185)).confidence)
        assertFalse(LyricsMatch.evaluate(query("Faded", "Alan Walker", 212), candidate("Faded (慢速版)", "Alan Walker", 213)).allowSynced)
    }

    @Test
    fun unknownCandidateLengthIsNeverConfident() {
        val verdict = LyricsMatch.evaluate(query("Faded", "Alan Walker", 212), candidate("Faded", "Alan Walker", null))
        assertEquals(PLAUSIBLE, verdict.confidence)
        assertEquals(REJECTED, LyricsMatch.evaluate(query("Faded", "Alan Walker", 212), LyricsCandidateInfo(null, "Alan Walker", 212_000)).confidence)
    }

    @Test
    fun anotherScriptMatchesOnlyOnArtistAndExactLength() {
        assertEquals(PLAUSIBLE, LyricsMatch.evaluate(query("アイドル", "YOASOBI", 213), candidate("Idol", "YOASOBI", 213)).confidence)
        assertEquals(REJECTED, LyricsMatch.evaluate(query("アイドル", "YOASOBI", 213), candidate("Idol", "YOASOBI", 230)).confidence)
    }

    @Test
    fun timingMustFitTheTrack() {
        fun lines(vararg seconds: Long) = seconds.map { LyricsEntry(it * 1000, "line $it") }
        assertTrue(LyricsMatch.timingFits(lines(12, 60, 180, 220), 233_000))
        assertFalse(LyricsMatch.timingFits(lines(12, 60, 180, 250), 233_000), "lines after the end")
        assertFalse(LyricsMatch.timingFits(lines(150, 160, 200), 233_000), "first line absurdly late")
        assertFalse(LyricsMatch.timingFits(lines(5, 30, 60), 233_000), "lyrics of a much shorter edit")
        assertFalse(LyricsMatch.timingFits(lines(6, 120, 272), 367_000), "the song's timing under its longer music video")
        assertTrue(LyricsMatch.timingFits(lines(5, 30, 60), null))
    }

    @Test
    fun recordsTheMatchedSongAndTheCheckInMlx() {
        val mlx = MlxBuilder().meta("LrcLib").song(title = "query title", artist = "query artist").line(1_000, "Hello").build()
        val matched = LyricsMatch.withMatchedSong(mlx, "Real Title", "Real Artist", 233_000)
        val document = assertNotNull(LyricsUtils.parseMlxDocument(matched))
        assertEquals("Real Title", document.song.title)
        assertEquals("Real Artist", document.song.artist)
        assertEquals(233_000, document.song.durationMs)
        assertEquals("Hello", document.lines.single().text)
        assertFalse(LyricsMatch.hasMatchCheck(matched))
        val checked = LyricsMatch.withMatchCheck(matched)
        assertTrue(LyricsMatch.hasMatchCheck(checked))
        assertEquals("LrcLib", LyricsUtils.parseMlxDocument(checked)?.meta?.lyricSource)
        assertEquals(checked, LyricsMatch.withMatchCheck(checked))
    }

    @Test
    fun unsyncedCopyKeepsTextAndSource() {
        val mlx = MlxBuilder().meta("KuGou").song(title = "T", artist = "A", durationMs = 1_000).line(1_000, "One").line(2_000, "Two").build()
        val plain = assertNotNull(LyricsMatch.toUnsyncedMlx(assertNotNull(LyricsUtils.parseMlxDocument(mlx)), "KuGou"))
        val lines = LyricsUtils.parseLyrics(plain)
        assertEquals(listOf("One", "Two"), lines.map { it.text })
        assertFalse(LyricsUtils.hasPlaybackTiming(lines))
    }
}
