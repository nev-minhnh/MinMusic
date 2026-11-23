package com.metrolist.core.api.download

import com.metrolist.desktop.storage.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SummaryBuilderTest {
    private fun build(
        files: List<FileStat> = emptyList(),
        states: Map<String, DownloadState> = emptyMap(),
        progresses: Map<String, Float> = emptyMap(),
        completed: Int = 0,
        titles: Map<String, String> = emptyMap(),
        paused: Boolean = false,
    ) = SummaryBuilder.build(files, states, progresses, completed, titles, "D:\\Music", true, paused)

    @Test
    fun emptyIsAllZero() {
        val s = build()
        assertEquals(0, s.count)
        assertEquals(0L, s.bytes)
        assertEquals(emptyList(), s.byQuality)
        assertEquals(ActiveInfo(0, 0), s.active)
        assertEquals("D:\\Music", s.folder)
    }

    @Test
    fun countsBytesAndSortsQualityByCountThenLabel() {
        val s =
            build(
                files =
                    listOf(
                        FileStat("audio/flac", 30),
                        FileStat("audio/webm", 10),
                        FileStat("audio/webm", 12),
                        FileStat("audio/mp4", 8),
                    ),
            )
        assertEquals(4, s.count)
        assertEquals(60L, s.bytes)
        assertEquals(listOf(QualityCount("Opus", 2), QualityCount("AAC", 1), QualityCount("FLAC", 1)), s.byQuality)
    }

    @Test
    fun activeCountsQueuedDownloadingAndFailedInTheBatch() {
        val s =
            build(
                states =
                    mapOf(
                        "a" to DownloadState.DOWNLOADING,
                        "b" to DownloadState.QUEUED,
                        "c" to DownloadState.FAILED,
                        "d" to DownloadState.DOWNLOADED,
                    ),
                progresses = mapOf("a" to 0.5f),
                completed = 2,
                titles = mapOf("a" to "Song A", "b" to "Song B"),
            )
        assertEquals(ActiveInfo(done = 2, total = 5, label = "Song A"), s.active)
    }

    @Test
    fun labelFallsBackToTheFirstQueuedWhenNothingRuns() {
        val s = build(states = mapOf("b" to DownloadState.QUEUED), titles = mapOf("b" to "Song B"))
        assertEquals(ActiveInfo(0, 1, "Song B"), s.active)
    }

    @Test
    fun noLabelWithoutATitle() {
        val s = build(states = mapOf("b" to DownloadState.QUEUED))
        assertNull(s.active.label)
    }

    @Test
    fun finishedOnlyIsNotActive() {
        val s = build(states = mapOf("d" to DownloadState.DOWNLOADED), completed = 0)
        assertEquals(ActiveInfo(0, 0), s.active)
    }

    @Test
    fun pausedAndFolderOkPassThrough() {
        val s = SummaryBuilder.build(emptyList(), emptyMap(), emptyMap(), 0, emptyMap(), "E:\\x", false, true)
        assertEquals(true, s.paused)
        assertEquals(false, s.folderOk)
    }
}
