package com.metrolist.core.api.shell

import com.metrolist.core.shell.normalizeJavaScriptResult
import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizeJsTest {
    @Test
    fun nullAndUndefinedAreEmpty() {
        assertEquals("", normalizeJavaScriptResult("null"))
        assertEquals("", normalizeJavaScriptResult("undefined"))
        assertEquals("", normalizeJavaScriptResult("  null \n"))
    }

    @Test
    fun aJsonStringLosesItsQuotesAndEscapes() {
        assertEquals("abc", normalizeJavaScriptResult("\"abc\""))
        assertEquals("a\"b", normalizeJavaScriptResult("\"a\\\"b\""))
        assertEquals("line1\nline2", normalizeJavaScriptResult("\"line1\\nline2\""))
    }

    @Test
    fun nonStringJsonIsReturnedAsText() {
        assertEquals("{\"a\":1}", normalizeJavaScriptResult("{\"a\":1}"))
        assertEquals("42", normalizeJavaScriptResult("42"))
    }

    @Test
    fun brokenJsonFallsBackToTrimmedQuotes() {
        assertEquals("abc", normalizeJavaScriptResult("\"abc"))
        assertEquals("plain", normalizeJavaScriptResult("plain"))
    }
}
