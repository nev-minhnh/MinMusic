package com.metrolist.core.api.crash

import com.metrolist.shared.crash.CrashReport

/** The `crash.pending` report as sent to the UI (api.md 3.15): `when` is epoch milliseconds. */
data class CrashSummaryDto(
    val whenMs: Long,
    val summary: String,
    val path: String,
)

/** Pure mapping of a stored crash report to what the UI shows. */
object CrashSummary {
    const val MAX_SUMMARY_CHARS = 300
    const val EMPTY_SUMMARY = "Unknown error"

    /** The first non-blank line of the stack trace (the exception and its message), trimmed and capped. */
    fun summaryOf(stackTrace: String): String =
        stackTrace
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(MAX_SUMMARY_CHARS)
            ?: EMPTY_SUMMARY

    fun of(
        report: CrashReport,
        path: String,
    ): CrashSummaryDto = CrashSummaryDto(report.timestamp, summaryOf(report.stackTrace), path)
}
