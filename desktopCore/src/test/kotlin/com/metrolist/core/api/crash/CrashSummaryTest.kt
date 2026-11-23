package com.metrolist.core.api.crash

import com.metrolist.shared.crash.CrashReport
import kotlin.test.Test
import kotlin.test.assertEquals

class CrashSummaryTest {
    @Test
    fun summaryIsTheFirstLineOfTheStackTrace() {
        val trace = "java.lang.IllegalStateException: player released twice\n\tat a.B.c(B.kt:1)\n\tat d.E.f(E.kt:2)"
        assertEquals("java.lang.IllegalStateException: player released twice", CrashSummary.summaryOf(trace))
    }

    @Test
    fun leadingBlankLinesAndCrlfAreSkipped() {
        assertEquals("boom", CrashSummary.summaryOf("\r\n  \r\nboom\r\n\tat x"))
    }

    @Test
    fun emptyTraceGetsAPlaceholder() {
        assertEquals(CrashSummary.EMPTY_SUMMARY, CrashSummary.summaryOf(""))
        assertEquals(CrashSummary.EMPTY_SUMMARY, CrashSummary.summaryOf(" \n\t\n"))
    }

    @Test
    fun longFirstLineIsCapped() {
        assertEquals(CrashSummary.MAX_SUMMARY_CHARS, CrashSummary.summaryOf("e".repeat(5000)).length)
    }

    @Test
    fun dtoCarriesTimestampSummaryAndPath() {
        val report = CrashReport(timestamp = 1_763_000_000_000, version = "2.0", platform = "Windows 11", stackTrace = "oops\nat x", id = "1763000000000-7")
        val dto = CrashSummary.of(report, "C:/data/metrolist/crashes/1763000000000-7.json")
        assertEquals(CrashSummaryDto(1_763_000_000_000, "oops", "C:/data/metrolist/crashes/1763000000000-7.json"), dto)
    }
}
