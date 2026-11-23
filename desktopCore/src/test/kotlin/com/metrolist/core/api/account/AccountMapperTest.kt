package com.metrolist.core.api.account

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountMapperTest {
    @Test
    fun signedOutHasNoYoutubeObject() {
        val json = AccountMapper.account(false, "Ignored", "x@y.z", "@h", "https://img")
        assertEquals("false", json["signedIn"]!!.jsonPrimitive.content)
        assertNull(json["youtube"])
    }

    @Test
    fun signedInMapsNameEmailHandleAvatar() {
        val json = AccountMapper.account(true, "Test User", "test@example.test", "@testuser", "https://img/a.png")
        assertEquals("true", json["signedIn"]!!.jsonPrimitive.content)
        val yt = json["youtube"]!!.jsonObject
        assertEquals("Test User", yt["name"]!!.jsonPrimitive.content)
        assertEquals("test@example.test", yt["email"]!!.jsonPrimitive.content)
        assertEquals("@testuser", yt["handle"]!!.jsonPrimitive.content)
        assertEquals("https://img/a.png", yt["avatar"]!!.jsonObject["src"]!!.jsonPrimitive.content)
        assertNull(yt["channels"])
    }

    @Test
    fun blankFieldsAreOmittedAndTheNameFallsBack() {
        val yt = AccountMapper.account(true, "", "mail@example.test", "", "")["youtube"]!!.jsonObject
        assertEquals("mail@example.test", yt["name"]!!.jsonPrimitive.content)
        assertNull(yt["handle"])
        assertNull(yt["avatar"])
        assertEquals("YouTube", AccountMapper.account(true, "", "", "", "")["youtube"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun channelsAreListedWhenGiven() {
        val yt = AccountMapper.account(true, "A", "", "", "", listOf("c1" to "Main", "c2" to "Music"))["youtube"]!!.jsonObject
        assertEquals(listOf("Main", "Music"), yt["channels"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun switchChannelPicksTheNextOneAndWraps() {
        assertEquals(1, AccountMapper.nextChannelIndex(listOf(true, false)))
        assertEquals(0, AccountMapper.nextChannelIndex(listOf(false, true)))
        assertEquals(2, AccountMapper.nextChannelIndex(listOf(false, true, false)))
        assertEquals(0, AccountMapper.nextChannelIndex(listOf(false, false)))
        assertNull(AccountMapper.nextChannelIndex(listOf(true)))
        assertNull(AccountMapper.nextChannelIndex(emptyList()))
    }

    @Test
    fun signinUrlBecomesAbsolute() {
        assertEquals("https://music.youtube.com/signin?a=1", AccountMapper.absoluteSigninUrl("/signin?a=1"))
        assertEquals("https://music.youtube.com/signin", AccountMapper.absoluteSigninUrl("signin"))
        assertEquals("https://accounts.google.com/x", AccountMapper.absoluteSigninUrl("https://accounts.google.com/x"))
        assertNull(AccountMapper.absoluteSigninUrl(" "))
        assertNull(AccountMapper.absoluteSigninUrl(null))
    }
}
