package com.metrolist.core.api.lt

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** Listen Together (api.md 3.13): the `lt.*` methods and the `lt` topic. */
object LtModule : ApiModule {
    override val handlers: Map<String, Handler> = LtApi.handlers
    override val topics: Map<String, Topic> = mapOf("lt" to LtApi.ltTopic)
}
