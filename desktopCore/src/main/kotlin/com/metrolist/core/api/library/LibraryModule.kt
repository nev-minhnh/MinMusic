package com.metrolist.core.api.library

import com.metrolist.core.api.ApiModule
import com.metrolist.core.api.LibraryApi
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.topic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The library, local data and history API (api.md 3.4) and the `library` topic (4). */
object LibraryModule : ApiModule {
    override val handlers: Map<String, Handler> =
        LibraryApi.handlers + UserAlbumApi.handlers + HistoryApi.handlers + TrackApi.handlers

    override val topics: Map<String, Topic> = mapOf("library" to LibraryTopic.topic)
}

/** `library.snapshot {counts, revision}`, then `library.changed {lists, revision}` (<= 4 Hz) and `userAlbums.changed {}`. */
object LibraryTopic {
    private const val MIN_INTERVAL_MS = 250L

    val topic: Topic =
        com.metrolist.core.server.topic { _, emit ->
            libraryReady()
            coroutineScope {
                val inbox = Channel<LibraryChange>(Channel.UNLIMITED)
                // Subscribe before the snapshot is built so no change falls between the two.
                launch(start = CoroutineStart.UNDISPATCHED) { LibraryChanges.events.collect { inbox.trySend(it) } }
                emit("library.snapshot", LibraryApi.snapshot())
                val coalescer = ChangeCoalescer(MIN_INTERVAL_MS)
                while (true) {
                    coalescer.add(inbox.receive())
                    drain(inbox, coalescer)
                    val wait = coalescer.waitMs(System.currentTimeMillis())
                    if (wait > 0) {
                        delay(wait)
                        drain(inbox, coalescer)
                    }
                    val out = coalescer.flush(System.currentTimeMillis()) ?: continue
                    emit(
                        "library.changed",
                        buildJsonObject {
                            put("lists", JsonArray(out.lists.map { JsonPrimitive(it) }))
                            put("revision", out.revision)
                        },
                    )
                    if (out.userAlbums) emit("userAlbums.changed", buildJsonObject { })
                }
            }
        }

    private fun drain(
        inbox: Channel<LibraryChange>,
        coalescer: ChangeCoalescer,
    ) {
        while (true) {
            val more = inbox.tryReceive().getOrNull() ?: return
            coalescer.add(more)
        }
    }
}
