package com.metrolist.core.api.shell

import com.metrolist.core.shell.upcomingIdsOf
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.MusicQueueState
import kotlin.test.Test
import kotlin.test.assertEquals

class UpcomingIdsTest {
    private fun state(
        ids: List<String>,
        current: Int,
    ) = MusicQueueState(
        entries =
            ids.mapIndexed { i, id ->
                MusicQueueEntry(i.toLong(), SongItem(id = id, title = id, thumbnail = null, artists = emptyList<ArtistTiny>(), album = null))
            },
        currentIndex = current,
    )

    @Test
    fun takesTheNextThreeAfterTheCurrentOne() {
        assertEquals(listOf("b", "c", "d"), upcomingIdsOf(state(listOf("a", "b", "c", "d", "e"), 0)))
    }

    @Test
    fun fewerThanThreeLeft() {
        assertEquals(listOf("e"), upcomingIdsOf(state(listOf("a", "b", "c", "d", "e"), 3)))
        assertEquals(emptyList(), upcomingIdsOf(state(listOf("a", "b"), 1)))
    }

    @Test
    fun skipsOtherSourcesAndKeepsCounting() {
        assertEquals(listOf("b", "c", "d"), upcomingIdsOf(state(listOf("a", "b", "zing:1", "c", "nct:2", "d"), 0)))
    }

    @Test
    fun noCurrentEntryStartsAtTheTop() {
        assertEquals(listOf("a", "b", "c"), upcomingIdsOf(state(listOf("a", "b", "c", "d"), -1)))
        assertEquals(emptyList(), upcomingIdsOf(MusicQueueState()))
    }
}
