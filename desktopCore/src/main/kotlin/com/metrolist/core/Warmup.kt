package com.metrolist.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * `--cds-warmup`: drives the real server path over a loopback WebSocket (hello, subscription, home from the fixture,
 * library window) so the AppCDS archive written at exit contains the classes of a normal session.
 */
internal object Warmup {
    fun run(
        port: Int,
        token: String,
    ) {
        val received = LinkedBlockingQueue<String>()
        val listener =
            object : WebSocket.Listener {
                private val buffer = StringBuilder()

                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<*>? {
                    buffer.append(data)
                    if (last) {
                        received.add(buffer.toString())
                        buffer.setLength(0)
                    }
                    webSocket.request(1)
                    return null
                }
            }
        val socket =
            HttpClient
                .newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI("ws://127.0.0.1:$port/rpc"), listener)
                .get(10, TimeUnit.SECONDS)
        val messages =
            listOf(
                """{"id":1,"m":"sys.hello","p":{"token":"$token","role":"ui","protocol":1,"client":"warmup/0","locale":"vi"}}""",
                """{"id":2,"m":"sub.open","p":{"topic":"player"}}""",
                """{"id":3,"m":"home.get","p":{}}""",
                """{"id":4,"m":"library.window","p":{"list":"songs","sort":{"by":"added","desc":true},"offset":0,"limit":50}}""",
                """{"id":5,"m":"library.counts","p":{}}""",
                """{"id":6,"m":"app.info","p":{}}""",
                """{"id":7,"m":"sys.ping","p":{}}""",
            )
        messages.forEach { socket.sendText(it, true).get(5, TimeUnit.SECONDS) }
        // 7 responses plus the player snapshot.
        var count = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (count < 8 && System.nanoTime() < deadline) {
            if (received.poll(500, TimeUnit.MILLISECONDS) != null) count++
        }
        CoreLog.info("warm-up received $count messages")
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS) }
    }
}
