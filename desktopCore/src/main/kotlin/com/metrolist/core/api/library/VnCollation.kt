package com.metrolist.core.api.library

import java.text.Normalizer

/**
 * Text helpers for the library lists (api.md 3.4, `vi` locale): [fold] for case- and diacritic-insensitive matching and
 * [key] for a Vietnamese-aware sort order. Pure; the JDK's `Collator` is not used because the runtime image may not carry
 * the `vi` collation data.
 *
 * Order of [key]: primary = base letters, where the Vietnamese letters a-breve, a-circumflex, e-circumflex, o-circumflex,
 * o-horn, u-horn and d-stroke follow their plain letter (a < ă < â < b); secondary = tone marks in the order
 * none, grave, hook, tilde, acute, dot below; tertiary = lower case before upper case.
 */
object VnCollation {
    /** Lower case without diacritics, d-stroke folded to d: `Mưa` and `mua` fold to the same string. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (ch.category == CharCategory.NON_SPACING_MARK) continue
            out.append(if (ch == 'đ' || ch == 'Đ') 'd' else ch.lowercaseChar())
        }
        return out.toString()
    }

    class SortKey(
        val primary: IntArray,
        val secondary: IntArray,
        val tertiary: IntArray,
    ) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            var result = compareArrays(primary, other.primary)
            if (result != 0) return result
            result = compareArrays(secondary, other.secondary)
            if (result != 0) return result
            return compareArrays(tertiary, other.tertiary)
        }
    }

    private const val CLASS_DIGIT = 1_000_000
    private const val CLASS_LATIN = 2_000_000
    private const val CLASS_OTHER_LETTER = 3_000_000

    fun key(text: String): SortKey {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val primary = ArrayList<Int>(decomposed.length)
        val secondary = ArrayList<Int>(decomposed.length)
        val tertiary = ArrayList<Int>(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val ch = decomposed[i]
            i++
            if (ch.category == CharCategory.NON_SPACING_MARK) continue // a stray mark with no base letter
            var tone = 0
            var modifier = 0
            while (i < decomposed.length && decomposed[i].category == CharCategory.NON_SPACING_MARK) {
                when (decomposed[i].code) {
                    0x0300 -> tone = 1
                    0x0309 -> tone = 2
                    0x0303 -> tone = 3
                    0x0301 -> tone = 4
                    0x0323 -> tone = 5
                    0x0306 -> modifier = 1
                    0x0302 -> modifier = 2
                    0x031B -> modifier = 3
                }
                i++
            }
            val lower = ch.lowercaseChar()
            val unit =
                when {
                    lower == 'đ' -> CLASS_LATIN + ('d' - 'a') * 8 + 1
                    lower in 'a'..'z' -> CLASS_LATIN + (lower - 'a') * 8 + modifier
                    ch.isDigit() -> CLASS_DIGIT + Character.digit(ch, 10).coerceAtLeast(0)
                    ch.isLetter() -> CLASS_OTHER_LETTER + lower.code
                    else -> ch.code // spaces and punctuation sort before digits and letters
                }
            primary += unit
            secondary += tone
            tertiary += if (ch != lower) 1 else 0
        }
        return SortKey(primary.toIntArray(), secondary.toIntArray(), tertiary.toIntArray())
    }

    private fun compareArrays(
        a: IntArray,
        b: IntArray,
    ): Int {
        val n = minOf(a.size, b.size)
        for (index in 0 until n) {
            if (a[index] != b[index]) return a[index].compareTo(b[index])
        }
        return a.size.compareTo(b.size)
    }
}
