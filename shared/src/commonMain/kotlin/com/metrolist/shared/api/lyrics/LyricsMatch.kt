/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.MlxDocument
import kotlin.math.abs
import kotlin.math.max

/** The playing track a lyrics candidate is checked against. */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val album: String? = null,
    val isVideo: Boolean = false,
)

/** What a provider says its lyrics belong to; null fields are unknown. */
data class LyricsCandidateInfo(
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
)

enum class LyricsMatchConfidence { REJECTED, PLAUSIBLE, CONFIDENT }

data class LyricsMatchVerdict(
    val confidence: LyricsMatchConfidence,
    /** False when the timing cannot be for this recording (other length or version): show the text unsynced. */
    val allowSynced: Boolean,
    val titleSimilarity: Double?,
    val artistSimilarity: Double?,
    val durationDeltaMs: Long?,
    val reason: String,
)

/**
 * Decides whether lyrics found by title search belong to the playing track. Pure functions only.
 *
 * Titles and artists are compared after folding case and diacritics (Vietnamese included) and after removing
 * upload decorations ("(Official MV)", "| Artist", "feat. X", "- Topic"). A wrong song is worse than no lyrics,
 * so anything below the thresholds is rejected rather than shown.
 */
object LyricsMatch {
    const val SYNCED_DURATION_TOLERANCE_MS = 3_000L
    const val PLAIN_DURATION_TOLERANCE_MS = 45_000L
    const val VIDEO_PLAIN_DURATION_TOLERANCE_MS = 120_000L
    const val CONFIDENT_TITLE_SIMILARITY = 0.9
    const val MIN_TITLE_SIMILARITY = 0.85
    const val MIN_ARTIST_SIMILARITY = 0.8

    fun evaluate(
        query: LyricsQuery,
        candidate: LyricsCandidateInfo,
    ): LyricsMatchVerdict {
        val queryTags = versionTags(query.title)
        val candidateTags = versionTags(candidate.title.orEmpty())
        val delta = durationDeltaMs(query.durationMs, candidate.durationMs)
        val title = candidate.title?.takeIf { it.isNotBlank() }?.let { titleSimilarity(query.title, query.artist, it, candidate.artist.orEmpty()) }
        val artist = candidate.artist?.let { artistSimilarity(query.artist, it, query.title, candidate.title.orEmpty()) }

        fun verdict(
            confidence: LyricsMatchConfidence,
            allowSynced: Boolean,
            reason: String,
        ) = LyricsMatchVerdict(confidence, allowSynced, title, artist, delta, reason)

        if (NON_VOCAL_TAGS.any { it in queryTags }) return verdict(LyricsMatchConfidence.REJECTED, false, "instrumental track")
        if (NON_VOCAL_TAGS.any { it in candidateTags }) return verdict(LyricsMatchConfidence.REJECTED, false, "instrumental candidate")
        if (artist != null && artist < MIN_ARTIST_SIMILARITY) return verdict(LyricsMatchConfidence.REJECTED, false, "other artist")
        val sameLength = delta != null && delta <= 1_500L
        val titleOk =
            when {
                title == null -> false
                title >= MIN_TITLE_SIMILARITY -> true
                // A title written in another script (アイドル / Idol) still matches on artist and exact length.
                else -> sameLength && artist != null && hasNonLatinLetters(query.title) != hasNonLatinLetters(candidate.title.orEmpty())
            }
        if (!titleOk) return verdict(LyricsMatchConfidence.REJECTED, false, "other title")
        // "Kẻ Say Tình 2" is another song than "Kẻ Say Tình", however close the words.
        if (titleNumbers(query.title, query.artist) != titleNumbers(candidate.title.orEmpty(), candidate.artist.orEmpty())) {
            return verdict(LyricsMatchConfidence.REJECTED, false, "other part")
        }
        val plainTolerance = if (query.isVideo || looksLikeVideoTitle(query.title)) VIDEO_PLAIN_DURATION_TOLERANCE_MS else PLAIN_DURATION_TOLERANCE_MS
        if (delta != null && delta > plainTolerance) return verdict(LyricsMatchConfidence.REJECTED, false, "other length")
        val sameVersion = (queryTags - VERSION_NEUTRAL_TAGS) == (candidateTags - VERSION_NEUTRAL_TAGS)
        val allowSynced = sameVersion && (delta == null || delta <= SYNCED_DURATION_TOLERANCE_MS)
        // A candidate of unknown length is never confident for a track of known length; a typed search has none.
        val lengthChecked = delta != null || query.durationMs == null
        val confident =
            sameVersion && title != null && title >= CONFIDENT_TITLE_SIMILARITY && lengthChecked &&
                (artist != null || query.artist.isBlank() && delta != null && delta <= SYNCED_DURATION_TOLERANCE_MS)
        return verdict(
            if (confident) LyricsMatchConfidence.CONFIDENT else LyricsMatchConfidence.PLAUSIBLE,
            allowSynced,
            if (!sameVersion) "other version" else if (confident) "match" else "weak match",
        )
    }

    /**
     * False for text that is not this song's lyrics even when the metadata matches: machine-made lyrics, or
     * lyrics in another script for a Vietnamese title (a translation, such as captions in another language).
     */
    fun textFits(
        queryTitle: String,
        lines: List<LyricsEntry>,
    ): Boolean {
        val text = lines.joinToString(" ") { it.text }
        if (MACHINE_LYRICS.containsMatchIn(text)) return false
        if (!VIETNAMESE_LETTERS.containsMatchIn(queryTitle.lowercase())) return true
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        val latin = letters.count { foldDiacritics(it.lowercaseChar().toString()).all { c -> c in 'a'..'z' } }
        return latin * 2 >= letters.length
    }

    /**
     * False when synced lines cannot belong to a recording of [durationMs]: lyrics past the end, an absurdly late
     * start, or a last line long before the end (timing made for a shorter edit, such as the song under its video).
     */
    fun timingFits(
        lines: List<LyricsEntry>,
        durationMs: Long?,
    ): Boolean {
        val duration = durationMs?.takeIf { it > 0 } ?: return true
        val starts = lines.filter { it.time >= 0 && it.text.isNotBlank() }.map { it.time }
        if (starts.isEmpty()) return true
        val first = starts.min()
        val last = starts.max()
        return last <= duration + 2_000L && first <= duration / 2 && duration - last <= maxOf(45_000L, duration / 5)
    }

    fun titleSimilarity(
        queryTitle: String,
        queryArtist: String,
        candidateTitle: String,
        candidateArtist: String = "",
    ): Double = similarity(coreTitle(queryTitle, queryArtist), coreTitle(candidateTitle, candidateArtist))

    /** Best match between any credited artist on each side; the artist may also hide in an upload's title. */
    fun artistSimilarity(
        queryArtist: String,
        candidateArtist: String,
        queryTitle: String = "",
        candidateTitle: String = "",
    ): Double? {
        val queryNames = artistNames(queryArtist)
        val candidateNames = artistNames(candidateArtist)
        if (queryNames.isEmpty() || candidateNames.isEmpty()) return null
        var best = 0.0
        for (q in queryNames) {
            for (c in candidateNames) best = max(best, nameSimilarity(q, c))
        }
        if (best < MIN_ARTIST_SIMILARITY) {
            val titleWords = " ${normalize(queryTitle)} "
            val candidateTitleWords = " ${normalize(candidateTitle)} "
            if (candidateNames.any { it.length >= 3 && titleWords.contains(" $it ") } ||
                queryNames.any { it.length >= 3 && candidateTitleWords.contains(" $it ") }
            ) {
                best = max(best, 0.9)
            }
        }
        return best
    }

    /** A title for searching: upload decorations removed, version words (Remix, Live) kept. */
    fun searchTitle(
        rawTitle: String,
        artist: String,
    ): String {
        var title = pickTitleSegment(rawTitle, artist)
        title = title.replace(DECORATION_BRACKETS, " ").replace(FEAT_BRACKETS, " ").replace(TRAILING_FEAT, " ")
        title = title.replace(HASHTAG, " ").replace(TRAILING_DECORATION, " ")
        return title.replace(SPACES, " ").trim().ifEmpty { rawTitle.trim() }
    }

    /** An artist for searching: channel suffixes ("- Topic", "Official", "VEVO") removed. */
    fun searchArtist(rawArtist: String): String =
        rawArtist
            .replace(CHANNEL_SUFFIX, "")
            .replace(SPACES, " ")
            .trim()
            .ifEmpty { rawArtist.trim() }

    /** The bare title compared between both sides: no brackets, featured artists or decorations. */
    fun coreTitle(
        rawTitle: String,
        artist: String,
    ): String {
        var title = pickTitleSegment(rawTitle, artist)
        title = title.replace(ANY_BRACKETS, " ").replace(TRAILING_FEAT, " ").replace(HASHTAG, " ")
        title = title.replace(TRAILING_DECORATION, " ").replace(TRAILING_VERSION_SUFFIX, " ")
        return normalize(title).ifEmpty { normalize(rawTitle) }
    }

    fun versionTags(rawTitle: String): Set<String> {
        val folded = " ${normalize(rawTitle)} "
        val bracketed = ANY_BRACKETS.findAll(rawTitle).joinToString(" ") { normalize(it.value) }
        val suffix = rawTitle.split(DASH_SEPARATOR).drop(1).joinToString(" ") { normalize(it) }
        val tagged = " $bracketed $suffix "
        return buildSet {
            for ((tag, words) in ANYWHERE_TAGS) if (words.any { folded.contains(" $it ") }) add(tag)
            for ((tag, words) in MARKED_TAGS) if (words.any { tagged.contains(" $it ") }) add(tag)
            for ((tag, words) in CJK_TAGS) if (words.any { folded.contains(it) }) add(tag)
        }
    }

    /** Lower case, no diacritics (đ -> d), punctuation to spaces, single spaces. */
    fun normalize(value: String): String {
        val folded = foldDiacritics(value.lowercase())
        val out = StringBuilder(folded.length)
        for (ch in folded) {
            when {
                ch == '&' -> out.append(" and ")
                ch.isLetterOrDigit() -> out.append(ch)
                else -> out.append(' ')
            }
        }
        return out.toString().replace(SPACES, " ").trim()
    }

    fun foldDiacritics(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            if (ch in '̀'..'ͯ') continue
            val mapped = FOLD_MAP[ch]
            if (mapped != null) out.append(mapped) else out.append(ch)
        }
        return out.toString()
    }

    fun similarity(
        a: String,
        b: String,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        if (compactA == compactB) return 1.0
        val tokensA = a.split(' ').toSet()
        val tokensB = b.split(' ').toSet()
        val tokenDice = 2.0 * tokensA.intersect(tokensB).size / (tokensA.size + tokensB.size)
        // Word order and spacing differ between catalogues; bigrams of the spaceless text catch that but also
        // score a title inside a longer one highly, so they count a little less than whole words.
        return max(tokenDice, bigramDice(compactA, compactB) * 0.9)
    }

    fun toUnsyncedMlx(
        document: MlxDocument,
        fallbackSource: String,
    ): String? {
        val texts = document.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return null
        val builder =
            MlxBuilder()
                .meta(document.meta.lyricSource ?: fallbackSource, sourceId = document.meta.sourceId)
                .song(title = document.song.title, artist = document.song.artist, durationMs = document.song.durationMs)
        texts.forEach(builder::unsyncedLine)
        return builder.build()
    }

    /**
     * Records in the MLX `[song]` section what the provider matched (its own title, artist and length), so the
     * repository can check the candidate against the playing track; blank values are left out.
     */
    fun withMatchedSong(
        mlx: String,
        title: String?,
        artist: String?,
        durationMs: Long?,
    ): String =
        withSectionValues(
            mlx,
            "song",
            mapOf(
                "title" to title?.trim()?.takeIf(String::isNotEmpty),
                "artist" to artist?.trim()?.takeIf(String::isNotEmpty),
                "duration_ms" to durationMs?.takeIf { it > 0 }?.toString(),
            ),
        )

    /** Marks lyrics chosen by the checked automatic lookup; older cached picks lack it and are looked up again. */
    fun withMatchCheck(mlx: String): String = withSectionValues(mlx, "meta", mapOf(MATCH_CHECK_KEY to MATCH_CHECK_VERSION))

    fun hasMatchCheck(mlx: String): Boolean =
        mlx
            .lineSequence()
            .map(String::trim)
            .takeWhile { it != "[song]" && it != "[lyrics]" }
            .any { it == "$MATCH_CHECK_KEY=$MATCH_CHECK_VERSION" }

    const val MATCH_CHECK_KEY = "match_check"
    const val MATCH_CHECK_VERSION = "1"

    private fun withSectionValues(
        mlx: String,
        section: String,
        values: Map<String, String?>,
    ): String {
        val header = "[$section]"
        val lines = mlx.lines()
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return mlx
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") && lines[it].trim().endsWith("]") } ?: lines.size
        val kept =
            lines.subList(start + 1, end).filterNot { line ->
                val key = line.substringBefore('=', "").trim()
                key.isNotEmpty() && key in values.keys
            }
        val added = values.mapNotNull { (key, value) -> value?.let { "$key=${it.replace('\n', ' ')}" } }
        return (lines.subList(0, start + 1) + added + kept + lines.subList(end, lines.size)).joinToString("\n")
    }

    private fun titleNumbers(
        title: String,
        artist: String,
    ): List<String> = coreTitle(title, artist).split(' ').filter { token -> token.isNotEmpty() && token.all { it.isDigit() } }

    private fun durationDeltaMs(
        a: Long?,
        b: Long?,
    ): Long? = if (a != null && b != null && a > 0 && b > 0) abs(a - b) else null

    private fun nameSimilarity(
        a: String,
        b: String,
    ): Double {
        if (a == b) return 1.0
        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        if (compactA == compactB) return 1.0
        val shorter = if (compactA.length <= compactB.length) compactA else compactB
        val longer = if (shorter === compactA) compactB else compactA
        if (shorter.length >= 4 && longer.contains(shorter)) return 0.9
        return similarity(a, b)
    }

    private fun artistNames(raw: String): List<String> =
        searchArtist(raw)
            .split(ARTIST_SEPARATOR)
            .map { normalize(it.replace(CHANNEL_SUFFIX, "")) }
            .filter { it.isNotEmpty() && it !in GENERIC_ARTISTS }
            .distinct()

    /** "Song | Artist | Official MV" and "Artist - Song" uploads: keep the part that is the song. */
    private fun pickTitleSegment(
        rawTitle: String,
        artist: String,
    ): String {
        val names = artistNames(artist)

        fun isArtist(part: String): Boolean {
            val normalized = normalize(part.replace(ANY_BRACKETS, " "))
            return normalized.isNotEmpty() && names.any { it == normalized || it.replace(" ", "") == normalized.replace(" ", "") }
        }
        var title = rawTitle
        val pipeParts = title.split(PIPE_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
        if (pipeParts.size > 1) title = pipeParts.firstOrNull { !isArtist(it) && normalize(it).isNotEmpty() } ?: pipeParts.first()
        val dashParts = title.split(DASH_SEPARATOR).map(String::trim).filter(String::isNotEmpty)
        if (dashParts.size == 2) {
            title =
                when {
                    isArtist(dashParts[0]) -> dashParts[1]
                    isArtist(dashParts[1]) -> dashParts[0]
                    else -> title
                }
        }
        return title
    }

    private fun looksLikeVideoTitle(title: String): Boolean = VIDEO_WORDS.containsMatchIn(normalize(title).let { " $it " })

    private fun hasNonLatinLetters(value: String): Boolean = foldDiacritics(value.lowercase()).any { it.isLetter() && it !in 'a'..'z' }

    private fun bigramDice(
        a: String,
        b: String,
    ): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val bigramsA = a.windowed(2).groupingBy { it }.eachCount().toMutableMap()
        val totalB = b.length - 1
        var shared = 0
        for (bigram in b.windowed(2)) {
            val left = bigramsA[bigram] ?: 0
            if (left > 0) {
                shared++
                bigramsA[bigram] = left - 1
            }
        }
        return 2.0 * shared / ((a.length - 1) + totalB)
    }

    private val FOLD_MAP: Map<Char, String> =
        buildMap {
            fun map(
                chars: String,
                base: String,
            ) = chars.forEach { put(it, base) }
            map("àáảãạăằắẳẵặâầấẩẫậäåāąǎ", "a")
            map("èéẻẽẹêềếểễệëēėęě", "e")
            map("ìíỉĩịîïīįǐı", "i")
            map("òóỏõọôồốổỗộơờớởỡợöøōǒ", "o")
            map("ùúủũụưừứửữựûüūůǔ", "u")
            map("ỳýỷỹỵÿ", "y")
            map("đð", "d")
            map("çćč", "c")
            map("ñńň", "n")
            map("śšş", "s")
            map("žźż", "z")
            map("ł", "l")
            map("ř", "r")
            map("ť", "t")
            map("ğ", "g")
            put('ß', "ss")
            put('æ', "ae")
            put('œ', "oe")
            put('’', "")
            put('\'', "")
        }

    private val SPACES = Regex("""\s+""")
    private val PIPE_SEPARATOR = Regex("""\s*[|｜]\s*""")
    private val DASH_SEPARATOR = Regex("""\s+[-–—]\s+""")
    private val ANY_BRACKETS = Regex("""[(\[【（][^)\]】）]*[)\]】）]""")
    private val DECORATION_WORDS =
        """official|offical|mv|m/v|music\s*video|video|audio|lyrics?|lyric\s*video|visuali[sz]er|vietsub|engsub|""" +
            """hd|4k|teaser|performance|explicit|clean|remaster(?:ed)?(?:\s*\d{4})?|\d{4}\s*remaster(?:ed)?|single|full|""" +
            """từ\s|ost\s|nhạc\s*phim"""
    private val DECORATION_BRACKETS =
        Regex("""[(\[【（]\s*(?:$DECORATION_WORDS)[^)\]】）]*[)\]】）]""", RegexOption.IGNORE_CASE)
    private val FEAT_BRACKETS = Regex("""[(\[【（]\s*(?:feat\.?|ft\.?|featuring|with|prod\.?)\s[^)\]】）]*[)\]】）]""", RegexOption.IGNORE_CASE)
    private val TRAILING_FEAT = Regex("""\s+(?:feat\.?|ft\.?|featuring|prod\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val HASHTAG = Regex("""#\S+""")
    private val TRAILING_DECORATION =
        Regex(
            """(?:\s+[-–—]?\s*(?:official\s+)?(?:music\s+video|mv|m/v|lyrics?\s+video|lyrics?|audio|visuali[sz]er|video|vietsub|4k|hd))+\s*$""",
            RegexOption.IGNORE_CASE,
        )
    private val TRAILING_VERSION_SUFFIX = Regex("""\s+[-–—]\s+.*$""")
    private val CHANNEL_SUFFIX =
        Regex("""\s*(?:[-–—]\s*topic|\bofficial\s+channel|\bofficial|\boffical|\bvevo|\bchannel)\s*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SEPARATOR =
        Regex("""\s*(?:[,;/&、×]|\s+x\s+|\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|và|and)\s+)\s*""", RegexOption.IGNORE_CASE)
    private val GENERIC_ARTISTS = setOf("various artists", "unknown", "unknown artist", "nhieu ca si", "va")
    private val MACHINE_LYRICS =
        Regex("""(?:generated|generad[ao]s?|gerad[ao]s?)\s+(?:by|por|pela|pelo)\s+(?:an?\s+)?ai\b|ai\s*生成|由ai""", RegexOption.IGNORE_CASE)
    private val VIETNAMESE_LETTERS = Regex("""[ăâđêôơưãĩõũạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ]""")
    private val VIDEO_WORDS = Regex(""" (?:mv|m v|music video|official video|video) """)

    /** No lyrics, or only a few seconds of them. */
    private val NON_VOCAL_TAGS = setOf("instrumental", "karaoke", "clip")

    /** Tags that do not change the words or their timing. */
    private val VERSION_NEUTRAL_TAGS = setOf("remaster")

    private val ANYWHERE_TAGS =
        mapOf(
            "remix" to listOf("remix", "vinahouse", "nightcore"),
            "instrumental" to listOf("instrumental", "off vocal"),
            "karaoke" to listOf("karaoke"),
            "sped" to listOf("sped up", "speed up", "slowed", "slowed reverb"),
            "lofi" to listOf("lofi", "lo fi"),
            "acoustic" to listOf("acoustic"),
            "remaster" to listOf("remaster", "remastered"),
        )

    /** Words that only mean a version inside brackets or after " - " ("Live Forever", "Beat It" are titles). */
    private val MARKED_TAGS =
        mapOf(
            "live" to listOf("live", "concert", "unplugged"),
            "cover" to listOf("cover"),
            "instrumental" to listOf("beat", "inst", "mr"),
            "remix" to listOf("mix", "edm", "house", "edit", "rework", "bootleg"),
            "piano" to listOf("piano", "guitar", "orchestra", "orchestral"),
            "demo" to listOf("demo"),
        )

    /** Chinese catalogue markers (KuGou lists clips, live cuts and slowed edits under the song's name). */
    private val CJK_TAGS =
        mapOf(
            "clip" to listOf("片段", "试听"),
            "instrumental" to listOf("伴奏", "纯音乐"),
            "live" to listOf("现场", "演唱会"),
            "sped" to listOf("慢速", "加速", "倍速", "降调", "升调"),
            "remix" to listOf("dj版", "混音"),
            "cover" to listOf("翻唱", "翻自", "原唱"),
        )
}
