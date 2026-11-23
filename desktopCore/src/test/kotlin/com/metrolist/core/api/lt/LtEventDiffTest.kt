package com.metrolist.core.api.lt

import com.metrolist.shared.listentogether.ListenTogetherJoinRequest
import com.metrolist.shared.listentogether.ListenTogetherSuggestion
import com.metrolist.shared.listentogether.network.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LtEventDiffTest {
    private fun join(id: String) = ListenTogetherJoinRequest(id, "name-$id")

    private fun suggestion(id: String) = ListenTogetherSuggestion(id, "u", "n", TrackInfo(id = "t$id", title = "T", artist = "A", duration = 1000))

    @Test
    fun joinRequestsAreEmittedOnce() {
        val diff = LtEventDiff()
        assertEquals(listOf("a"), diff.joinRequests(listOf(join("a"))).map { it.userId })
        assertTrue(diff.joinRequests(listOf(join("a"))).isEmpty())
        assertEquals(listOf("b"), diff.joinRequests(listOf(join("a"), join("b"))).map { it.userId })
        assertTrue(diff.joinRequests(listOf(join("a"), join("b"))).isEmpty())
    }

    @Test
    fun aRequestThatLeftAndReturnsIsNewAgain() {
        val diff = LtEventDiff()
        diff.joinRequests(listOf(join("a")))
        assertTrue(diff.joinRequests(emptyList()).isEmpty())
        assertEquals(1, diff.joinRequests(listOf(join("a"))).size)
    }

    @Test
    fun suggestionsAreEmittedOnce() {
        val diff = LtEventDiff()
        assertEquals(listOf("s1", "s2"), diff.suggestions(listOf(suggestion("s1"), suggestion("s2"))).map { it.id })
        assertTrue(diff.suggestions(listOf(suggestion("s1"), suggestion("s2"))).isEmpty())
        assertEquals(listOf("s3"), diff.suggestions(listOf(suggestion("s2"), suggestion("s3"))).map { it.id })
    }

    @Test
    fun separateSubscriptionsEachSeeTheBacklog() {
        val pending = listOf(join("a"))
        assertEquals(1, LtEventDiff().joinRequests(pending).size)
        assertEquals(1, LtEventDiff().joinRequests(pending).size)
    }

    @Test
    fun inputRules() {
        assertEquals("ABCD1234", LtInput.code(" abcd1234 "))
        assertEquals("K7QX2M", LtInput.code("k7qx2m"))
        assertNull(LtInput.code("ab"))
        assertNull(LtInput.code("abcd12345"))
        assertNull(LtInput.code("ab cd1"))
        assertNull(LtInput.code(""))
        assertEquals("Minh", LtInput.name("  Minh "))
        assertNull(LtInput.name("   "))
        assertEquals(32, LtInput.name("x".repeat(80))!!.length)
    }
}
