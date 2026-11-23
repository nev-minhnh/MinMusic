package com.metrolist.core.api.shell

import com.metrolist.core.server.ApiException
import com.metrolist.core.server.ShellFrames
import com.metrolist.core.shell.ShellChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShellChannelTest {
    private val sent = CopyOnWriteArrayList<JsonObject>()

    @BeforeTest
    fun setUp() {
        ShellChannel.resetForTest()
        sent.clear()
    }

    @AfterTest
    fun tearDown() = ShellChannel.resetForTest()

    private fun attach(): Any = ShellChannel.attach { sent += it }

    private suspend fun awaitSent(count: Int) {
        withTimeout(2_000) { while (sent.size < count) delay(5) }
    }

    private fun sidOf(frame: JsonObject): Long = frame["sid"]!!.jsonPrimitive.content.toLong()

    private fun reply(
        sid: Long,
        result: JsonObject,
    ) = buildJsonObject {
        put("sid", sid)
        put("r", result)
    }

    @Test
    fun noShellIsUnsupported() =
        runBlocking {
            val error = assertFailsWith<ApiException> { ShellChannel.request("shell.po.available", JsonObject(emptyMap()), 100) }
            assertEquals("unsupported", error.code)
            assertFalse(ShellChannel.connected.value)
        }

    @Test
    fun requestFrameCarriesSidMethodAndParams() =
        runBlocking {
            attach()
            val call = async { ShellChannel.request("shell.po.reset", buildJsonObject { put("x", 1) }, 2_000) }
            awaitSent(1)
            val frame = sent[0]
            assertEquals("shell.po.reset", frame["m"]!!.jsonPrimitive.content)
            assertEquals(1, (frame["p"] as JsonObject)["x"]!!.jsonPrimitive.int)
            assertTrue("id" !in frame)
            assertTrue(ShellChannel.complete(reply(sidOf(frame), JsonObject(emptyMap()))))
            assertEquals(JsonObject(emptyMap()), call.await())
        }

    @Test
    fun answersAreMatchedBySidEvenOutOfOrder() =
        runBlocking {
            attach()
            val first = async { ShellChannel.request("a", JsonObject(emptyMap()), 2_000) }
            val second = async { ShellChannel.request("b", JsonObject(emptyMap()), 2_000) }
            awaitSent(2)
            val byMethod = sent.associateBy { it["m"]!!.jsonPrimitive.content }
            assertNotEquals(sidOf(byMethod.getValue("a")), sidOf(byMethod.getValue("b")))
            ShellChannel.complete(reply(sidOf(byMethod.getValue("b")), buildJsonObject { put("who", "b") }))
            ShellChannel.complete(reply(sidOf(byMethod.getValue("a")), buildJsonObject { put("who", "a") }))
            assertEquals("a", (first.await() as JsonObject)["who"]!!.jsonPrimitive.content)
            assertEquals("b", (second.await() as JsonObject)["who"]!!.jsonPrimitive.content)
        }

    @Test
    fun shellErrorBecomesApiException() =
        runBlocking {
            attach()
            val call = async { runCatching { ShellChannel.request("shell.webLogin", JsonObject(emptyMap()), null) } }
            awaitSent(1)
            val error =
                buildJsonObject {
                    put("code", "timeout")
                    put("msg", "PO-token script timed out")
                }
            ShellChannel.complete(
                buildJsonObject {
                    put("sid", sidOf(sent[0]))
                    put("e", error)
                },
            )
            val failure = call.await().exceptionOrNull() as ApiException
            assertEquals("timeout", failure.code)
            assertEquals("PO-token script timed out", failure.message)
        }

    @Test
    fun timeoutIsATimeoutError() =
        runBlocking {
            attach()
            val error = assertFailsWith<ApiException> { ShellChannel.request("slow", JsonObject(emptyMap()), 50) }
            assertEquals("timeout", error.code)
        }

    @Test
    fun nullTimeoutWaitsForTheAnswer() =
        runBlocking {
            attach()
            val call = async { ShellChannel.request("shell.webLogin", JsonObject(emptyMap()), null) }
            awaitSent(1)
            delay(150)
            assertTrue(call.isActive)
            ShellChannel.complete(reply(sidOf(sent[0]), buildJsonObject { put("cancelled", true) }))
            assertEquals("true", ((call.await() as JsonObject)["cancelled"] as JsonPrimitive).content)
        }

    @Test
    fun detachFailsPendingRequestsAndClearsConnected() =
        runBlocking {
            val handle = attach()
            assertTrue(ShellChannel.connected.value)
            val call = async { runCatching { ShellChannel.request("shell.webLogin", JsonObject(emptyMap()), null) } }
            awaitSent(1)
            ShellChannel.detach(handle)
            assertFalse(ShellChannel.connected.value)
            assertEquals("unsupported", (call.await().exceptionOrNull() as ApiException).code)
        }

    @Test
    fun aNewerConnectionReplacesTheOlderAndDetachOfTheOldOneIsIgnored() {
        val old = attach()
        val generation = ShellChannel.generation
        val newer = ShellChannel.attach { }
        assertNotEquals(generation, ShellChannel.generation)
        ShellChannel.detach(old)
        assertTrue(ShellChannel.connected.value)
        ShellChannel.detach(newer)
        assertFalse(ShellChannel.connected.value)
    }

    @Test
    fun aFailingSendIsUnsupported() =
        runBlocking {
            ShellChannel.attach { throw java.io.IOException("closed") }
            val error = assertFailsWith<ApiException> { ShellChannel.request("x", JsonObject(emptyMap()), 100) }
            assertEquals("unsupported", error.code)
        }

    @Test
    fun unknownSidIsNotCompleted() {
        assertFalse(ShellChannel.complete(reply(999, JsonObject(emptyMap()))))
        assertFalse(ShellChannel.complete(buildJsonObject { put("id", 1) }))
    }

    @Test
    fun shellFramesOnlyAcceptSidWithoutId() =
        runBlocking {
            attach()
            val call = async { ShellChannel.request("shell.po.available", JsonObject(emptyMap()), 2_000) }
            awaitSent(1)
            val sid = sidOf(sent[0])
            assertFalse(ShellFrames.offer("not json"))
            assertFalse(ShellFrames.offer("{\"id\":$sid,\"r\":{}}"))
            assertTrue(ShellFrames.offer("{\"sid\":$sid,\"r\":{\"available\":true}}"))
            assertEquals("true", ((call.await() as JsonObject)["available"] as JsonPrimitive).content)
        }
}
