package com.metrolist.core.shell

import com.metrolist.core.server.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The core -> shell request channel (api.md 1.2 `SrvReq`/`SrvRes`, section 5). It sends `{sid, m, p}` on the connection that
 * said `sys.hello` with `role=shell` and completes a request on the matching `{sid, r}` / `{sid, e}`.
 * The transport is a plain send function, so the correlation logic is unit-testable without a socket.
 */
object ShellChannel {
    private class Link(
        val send: suspend (JsonObject) -> Unit,
    )

    private class Pending(
        val link: Link,
        val reply: CompletableDeferred<JsonObject>,
    )

    private val link = AtomicReference<Link?>(null)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val nextSid = AtomicLong(1)
    private val connectedFlow = MutableStateFlow(false)
    private val generationCounter = AtomicLong(0)

    /** Counts shell connections; it changes on every [attach], because the shell's webview state is lost with a new connection. */
    val generation: Long get() = generationCounter.get()

    /** True while a shell connection is registered. */
    val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

    /** Registers the shell connection (a newer one replaces an older one). Returns the handle for [detach]. */
    fun attach(send: suspend (JsonObject) -> Unit): Any {
        val fresh = Link(send)
        generationCounter.incrementAndGet()
        val old = link.getAndSet(fresh)
        if (old != null) failPending(old)
        connectedFlow.value = true
        return fresh
    }

    /** Unregisters the connection that [attach] returned [handle] for; a no-op when it was already replaced. */
    fun detach(handle: Any) {
        val gone = handle as? Link ?: return
        if (link.compareAndSet(gone, null)) {
            connectedFlow.value = false
            failPending(gone)
        }
    }

    /** Completes the request a `{sid, r|e}` frame answers. Returns false when the frame is not a reply to a pending request. */
    fun complete(frame: JsonObject): Boolean {
        val sid = (frame["sid"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong() ?: return false
        val entry = pending.remove(sid) ?: return false
        entry.reply.complete(frame)
        return true
    }

    /**
     * Sends [m] with [p] and waits for the answer. [timeoutMs] null means no timeout (`shell.webLogin`).
     * No shell: `unsupported`. A shell error `{code, msg}` becomes an [ApiException] with the same code and message.
     */
    suspend fun request(
        m: String,
        p: JsonObject,
        timeoutMs: Long?,
    ): JsonElement {
        val current = link.get() ?: throw ApiException("unsupported", "shell not connected")
        val sid = nextSid.getAndIncrement()
        val reply = CompletableDeferred<JsonObject>()
        pending[sid] = Pending(current, reply)
        try {
            try {
                current.send(
                    buildJsonObject {
                        put("sid", sid)
                        put("m", m)
                        put("p", p)
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw ApiException("unsupported", "shell connection lost")
            }
            val frame =
                if (timeoutMs == null) {
                    reply.await()
                } else {
                    withTimeoutOrNull(timeoutMs) { reply.await() }
                        ?: throw ApiException("timeout", "shell request $m timed out", retryable = true)
                }
            val error = frame["e"] as? JsonObject
            if (error != null) {
                val code = (error["code"] as? JsonPrimitive)?.content ?: "internal"
                val msg = (error["msg"] as? JsonPrimitive)?.content ?: code
                throw ApiException(code, msg)
            }
            return frame["r"] ?: JsonNull
        } finally {
            pending.remove(sid)
        }
    }

    private fun failPending(gone: Link) {
        pending.entries.filter { it.value.link === gone }.forEach { (sid, entry) ->
            if (pending.remove(sid, entry)) {
                entry.reply.complete(
                    buildJsonObject {
                        put("sid", sid)
                        put(
                            "e",
                            buildJsonObject {
                                put("code", "unsupported")
                                put("msg", "shell disconnected")
                            },
                        )
                    },
                )
            }
        }
    }

    /** Unit tests only: forget the connection and every pending request. */
    internal fun resetForTest() {
        link.set(null)
        connectedFlow.value = false
        pending.clear()
    }
}
