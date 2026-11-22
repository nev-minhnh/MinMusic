package com.metrolist.core.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** api.md 1.5 error envelope. */
class ApiException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
    val i18n: String? = null,
    val details: JsonObject? = null,
) : Exception(message) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("code", code)
            put("msg", message ?: code)
            if (i18n != null) put("i18n", i18n)
            put("retryable", retryable)
            if (details != null) put("details", details)
        }
}

fun badRequest(message: String): Nothing = throw ApiException("bad_request", message)

fun unsupported(message: String): Nothing = throw ApiException("unsupported", message)

fun notFound(message: String): Nothing = throw ApiException("not_found", message)

/** One client connection (role ui, mini or shell). */
class Conn(
    val session: DefaultWebSocketServerSession,
    val role: String,
    val client: String,
    val locale: String,
) {
    val subs = ConcurrentHashMap<Int, Job>()
    private val nextSub = AtomicInteger(1)
    private val sendMutex = Mutex()

    fun newSubId(): Int = nextSub.getAndIncrement()

    suspend fun send(message: JsonElement) {
        sendMutex.withLock { session.send(Frame.Text(message.toString())) }
    }

    suspend fun respond(
        id: Long,
        result: JsonElement?,
        error: ApiException?,
    ) {
        send(
            buildJsonObject {
                put("id", id)
                if (error != null) put("e", error.toJson()) else put("r", result ?: JsonNull)
            },
        )
    }
}

typealias Handler = suspend (params: JsonObject, conn: Conn) -> JsonElement

/** Gives a lambda the [Handler] type so it can sit in a map next to others. */
fun handler(block: Handler): Handler = block

/** A subscription topic: emits `<topic>.snapshot` first, then deltas, through the `emit` callback. */
typealias Topic = suspend (params: JsonObject, emit: suspend (event: String, payload: JsonElement) -> Unit) -> Unit

fun topic(block: Topic): Topic = block

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

fun JsonObject.int(key: String): Int? = long(key)?.toInt()

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.requireStr(key: String): String = str(key) ?: badRequest("missing string param '$key'")

fun JsonObject.requireLong(key: String): Long = long(key) ?: badRequest("missing number param '$key'")

fun JsonObject.requireDouble(key: String): Double = double(key) ?: badRequest("missing number param '$key'")

fun JsonObject.requireBool(key: String): Boolean = bool(key) ?: badRequest("missing boolean param '$key'")

fun emptyResult(): JsonObject = JsonObject(emptyMap())

fun epochNow(): JsonPrimitive = JsonPrimitive(System.currentTimeMillis())
