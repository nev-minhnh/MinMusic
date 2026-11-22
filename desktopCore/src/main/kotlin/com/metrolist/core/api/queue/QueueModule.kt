package com.metrolist.core.api.queue

import com.metrolist.core.api.ApiModule
import com.metrolist.core.api.PlayerApi
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** Player and queue: the `player.*` commands (PlayerApi), `queue.*`, and the `player` and `queue` topics. */
object QueueModule : ApiModule {
    override val handlers: Map<String, Handler> = PlayerApi.handlers + QueueApi.handlers
    override val topics: Map<String, Topic> = mapOf("player" to PlayerApi.playerTopic, "queue" to QueueApi.queueTopic)
}
