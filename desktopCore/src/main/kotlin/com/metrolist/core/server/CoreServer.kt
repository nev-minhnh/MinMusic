package com.metrolist.core.server

import com.metrolist.core.CoreBackend
import com.metrolist.core.CoreConfig
import com.metrolist.core.CoreLog
import com.metrolist.core.shell.ShellChannel
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Ktor CIO on 127.0.0.1:0 with the `/rpc` WebSocket (api.md section 1): Origin check, `sys.hello` token handshake
 * (close 4401), a dispatch table, subscriptions with per-subscription `seq`, and the error envelope.
 */
class CoreServer(
    private val dev: Boolean,
    private val handlers: Map<String, Handler>,
    private val topics: Map<String, Topic>,
    private val capabilities: List<String> = emptyList(),
) {
    private val server =
        embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets) {
                maxFrameSize = MAX_FRAME_BYTES
            }
            routing {
                webSocket("/rpc") { handleSession(this) }
            }
        }

    @Volatile
    var port: Int = 0
        private set

    fun start(): Int {
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        return port
    }

    fun stop() {
        runCatching { server.stop(100, 300) }
    }

    private fun originAllowed(origin: String?): Boolean =
        origin.isNullOrBlank() || origin == "http://tauri.localhost" || origin == "https://tauri.localhost" ||
            (dev && origin == "http://localhost:5173")

    private fun tokenMatches(candidate: String?): Boolean =
        candidate != null &&
            CoreConfig.token.isNotEmpty() &&
            MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), CoreConfig.token.toByteArray(Charsets.UTF_8))

    private suspend fun handleSession(session: DefaultWebSocketServerSession) {
        val origin = session.call.request.headers[HttpHeaders.Origin]
        if (!originAllowed(origin)) {
            CoreLog.info("rejected connection with Origin '$origin'")
            session.close(CloseReason(4403, "bad origin"))
            return
        }
        // sys.hello must arrive within 2 s.
        val firstFrame = withTimeoutOrNull(HELLO_TIMEOUT_MS) { session.incoming.receiveCatching().getOrNull() }
        val hello = (firstFrame as? Frame.Text)?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
        val helloId = hello?.long("id")
        val helloParams = hello?.obj("p")
        if (hello == null || hello.str("m") != "sys.hello" || helloParams == null || !tokenMatches(helloParams.str("token"))) {
            session.close(CloseReason(4401, "unauthorized"))
            return
        }
        val role = helloParams.str("role")
        if (role !in ROLES) {
            session.close(CloseReason(4401, "bad role"))
            return
        }
        val protocol = helloParams.int("protocol") ?: CoreConfig.PROTOCOL
        if (protocol !in (CoreConfig.PROTOCOL - 1)..CoreConfig.PROTOCOL) {
            session.close(CloseReason(4401, "protocol mismatch"))
            return
        }
        val conn = Conn(session, role!!, helloParams.str("client").orEmpty(), helloParams.str("locale").orEmpty())
        conn.respond(
            helloId ?: 0,
            buildJsonObject {
                put("protocol", CoreConfig.PROTOCOL)
                put("core", CoreConfig.VERSION)
                put("sessionId", UUID.randomUUID().toString())
                put("capabilities", JsonArray(capabilities.map { JsonPrimitive(it) }))
                put("serverTime", System.currentTimeMillis())
            },
            null,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // M3-2: the shell connection is the core's channel to the shell (shell.webLogin, shell.po.*).
        val shellHandle = if (role == "shell") ShellFrames.attach(conn) else null
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (shellHandle != null && ShellFrames.offer(text)) continue
                scope.launch { handleRequest(conn, scope, text) }
            }
        } finally {
            if (shellHandle != null) ShellChannel.detach(shellHandle)
            conn.subs.values.forEach { it.cancel() }
            scope.cancel()
        }
    }

    private suspend fun handleRequest(
        conn: Conn,
        scope: CoroutineScope,
        text: String,
    ) {
        val request = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = request.long("id") ?: return
        val method = request.str("m")
        val params = request.obj("p") ?: JsonObject(emptyMap())
        try {
            if (method == null) badRequest("missing method")
            when (method) {
                "sub.open" -> openSubscription(conn, scope, id, params)
                "sub.close" -> {
                    val sub = params.int("sub") ?: badRequest("missing 'sub'")
                    conn.subs.remove(sub)?.cancel()
                    conn.respond(id, JsonObject(emptyMap()), null)
                }

                else -> {
                    val handler = handlers[method] ?: throw ApiException("bad_request", "unknown method $method")
                    conn.respond(id, handler(params, conn), null)
                }
            }
        } catch (error: ApiException) {
            conn.respond(id, null, error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CoreLog.error("handler $method failed (params ${ParamRedaction.describe(method, params)})", error)
            conn.respond(id, null, ApiException("internal", error.message ?: error::class.java.simpleName))
        }
    }

    private suspend fun openSubscription(
        conn: Conn,
        scope: CoroutineScope,
        id: Long,
        params: JsonObject,
    ) {
        val topicName = params.str("topic") ?: badRequest("missing 'topic'")
        val topic = topics[topicName] ?: throw ApiException("unsupported", "unknown topic $topicName")
        val sub = conn.newSubId()
        val seq = AtomicLong(0)
        val order = Mutex()
        // Lazy: the response (with the sub id) must reach the client before the first event.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    topic(params.obj("params") ?: JsonObject(emptyMap())) { event, payload ->
                        order.withLock {
                            conn.send(
                                buildJsonObject {
                                    put("ev", event)
                                    put("p", payload)
                                    put("sub", sub)
                                    put("seq", seq.incrementAndGet())
                                },
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    CoreLog.error("topic $topicName failed", error)
                }
            }
        conn.subs[sub] = job
        conn.respond(id, buildJsonObject { put("sub", sub) }, null)
        job.start()
    }

    companion object {
        private const val MAX_FRAME_BYTES = 4L * 1024 * 1024
        private const val HELLO_TIMEOUT_MS = 2_000L
        private val ROLES = setOf("ui", "mini", "shell")
    }
}

/** Awaits the backend, mapping an init failure to a retryable `internal` error. */
suspend fun backendReady() {
    try {
        CoreBackend.await()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw ApiException("internal", "core backend failed to start: ${error.message}", retryable = true)
    }
}
