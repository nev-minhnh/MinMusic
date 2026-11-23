package com.metrolist.core.api.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VnCollationTest {
    private fun sorted(vararg words: String): List<String> = words.sortedBy { VnCollation.key(it) }

    @Test
    fun foldIgnoresCaseAndDiacritics() {
        assertEquals("mua", VnCollation.fold("Mưa"))
        assertEquals("mua", VnCollation.fold("MUA"))
        assertEquals("duong", VnCollation.fold("Đường"))
        assertEquals("nguoi tinh", VnCollation.fold("Người Tình"))
        assertTrue(VnCollation.fold("Nhớ Nước Mắt Lặng Lẽ Mưa").contains(VnCollation.fold("mưa")))
    }

    @Test
    fun foldOfComposedAndDecomposedFormsAgree() {
        assertEquals(VnCollation.fold("ấ"), VnCollation.fold("ấ"))
    }

    @Test
    fun vietnameseLettersFollowTheirBaseLetter() {
        assertEquals(listOf("a", "ă", "â", "b"), sorted("b", "â", "a", "ă"))
        assertEquals(listOf("d", "đ", "e"), sorted("e", "đ", "d"))
        assertEquals(listOf("o", "ô", "ơ", "p"), sorted("p", "ơ", "o", "ô"))
    }

    @Test
    fun tonesFollowTheVietnameseOrder() {
        // none, grave, hook above, tilde, acute, dot below
        val expected = listOf("ma", "mà", "mả", "mã", "má", "mạ")
        assertEquals(expected, sorted("mạ", "má", "mã", "mả", "mà", "ma"))
    }

    @Test
    fun baseLettersOutweighTones() {
        // a tone mark on the second letter never moves a word past a word whose second letter is later in the alphabet
        assertEquals(listOf("mạ", "mb"), sorted("mb", "mạ"))
    }

    @Test
    fun lowerCaseComesBeforeUpperCaseOnlyWhenEverythingElseIsEqual() {
        assertEquals(listOf("abc", "Abc"), sorted("Abc", "abc"))
        assertEquals(listOf("Abc", "abd"), sorted("abd", "Abc"))
    }

    @Test
    fun punctuationAndDigitsSortBeforeLetters() {
        assertEquals(listOf("(Live)", "1", "a"), sorted("a", "1", "(Live)"))
    }

    @Test
    fun shorterPrefixFirst() {
        assertEquals(listOf("ma", "mai"), sorted("mai", "ma"))
    }
}
