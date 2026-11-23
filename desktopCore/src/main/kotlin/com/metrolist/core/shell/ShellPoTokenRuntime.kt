package com.metrolist.core.shell

import com.metrolist.core.CoreLog
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.source.isYouTubeId
import com.metrolist.shared.youtube.playback.PAGE_BOUND_TOKEN_SCRIPT
import com.metrolist.shared.youtube.playback.PageBoundPoTokenJavaScriptRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The page-bound PO-token runtime backed by the shell's hidden webview (architecture 5.2). The shell loads
 * `PAGE_BOUND_TOKEN_ORIGIN_URL` with the same user agent as `PAGE_BOUND_PO_TOKEN_USER_AGENT` on Windows (both verified equal).
 * The shell does not inject `PAGE_BOUND_TOKEN_SCRIPT` as an init script (v1 does, through its WebView settings), so this runtime
 * sends it once per page load as the first evaluation: after every (re)connect of the shell and after every [reset].
 */
object ShellPoTokenRuntime : PageBoundPoTokenJavaScriptRuntime {
    private const val EVALUATE_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 11_000L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedAvailable = false

    // The shell connection generation the init script was installed on; -1 = not installed.
    private var installedGeneration = -1L

    init {
        scope.launch {
            ShellChannel.connected.collectLatest { connected ->
                if (!connected) {
                    cachedAvailable = false
                    return@collectLatest
                }
                refreshAvailable()
            }
        }
    }

    override val available: Boolean
        get() = ShellChannel.connected.value && cachedAvailable

    override suspend fun evaluate(script: String): String =
        mutex.withLock {
            val generation = ShellChannel.generation
            if (installedGeneration != generation) {
                send(PAGE_BOUND_TOKEN_SCRIPT)
                installedGeneration = generation
            }
            val result =
                try {
                    send(script)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // The page may have been reloaded behind our back: install the script again next time.
                    installedGeneration = -1L
                    throw error
                }
            normalizeJavaScriptResult(result)
        }

    /**
     * Never throws: the stream recovery calls it through `invalidateAttestation()` outside its own try (W6). Without a
     * shell there is no webview to reset; "shell not connected" escaped as an uncaught exception there, the recovery
     * coroutine died, and playback stayed silent with `playing = true` after a 403 on a seek.
     */
    override suspend fun reset() {
        mutex.withLock {
            installedGeneration = -1L
            if (!ShellChannel.connected.value) return@withLock
            try {
                ShellChannel.request("shell.po.reset", JsonObject(emptyMap()), REQUEST_TIMEOUT_MS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CoreLog.info("shell.po.reset failed: ${error.message}")
            }
        }
    }

    // ShellChannel.request(m: String, p: JsonObject, timeoutMs: Long?): JsonElement; shell.po.evaluate -> {result: string}
    private suspend fun send(script: String): String {
        val answer =
            withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
                ShellChannel.request(
                    "shell.po.evaluate",
                    buildJsonObject {
                        put("script", script)
                        put("timeoutMs", EVALUATE_TIMEOUT_MS)
                    },
                    REQUEST_TIMEOUT_MS,
                )
            } ?: throw IllegalStateException("PO token evaluate timeout")
        return ((answer as? JsonObject)?.get("result") as? JsonPrimitive)?.content
            ?: throw IllegalStateException("PO token evaluate returned no result")
    }

    private suspend fun refreshAvailable() {
        repeat(3) { attempt ->
            try {
                // shell.po.available -> {available: boolean}
                val answer = ShellChannel.request("shell.po.available", JsonObject(emptyMap()), 5_000L)
                cachedAvailable = ((answer as? JsonObject)?.get("available") as? JsonPrimitive)?.content == "true"
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CoreLog.info("shell.po.available failed (attempt ${attempt + 1}): ${error.message}")
                delay(1_000L)
            }
        }
        cachedAvailable = false
    }
}

/** The raw `ExecuteScript` JSON string to the plain value: `null`/`undefined` become empty, a JSON string loses its quotes. */
internal fun normalizeJavaScriptResult(value: String): String {
    val raw = value.trim()
    if (raw == "null" || raw == "undefined") return ""
    return runCatching { Json.decodeFromString<String>(raw) }.getOrDefault(raw.trim('"'))
}

/** The next [limit] YouTube ids after the current queue entry, for PO-token pre-minting. */
fun upcomingIdsOf(
    state: MusicQueueState,
    limit: Int = 3,
): List<String> =
    state.entries
        .drop((state.currentIndex + 1).coerceAtLeast(0))
        .map { it.song.id }
        .filter(::isYouTubeId)
        .take(limit)

/** [upcomingIds] of the live player queue; empty when the queue is not readable yet. */
fun upcomingIds(): List<String> = runCatching { upcomingIdsOf(PlayerState.queue.state.value) }.getOrDefault(emptyList())
