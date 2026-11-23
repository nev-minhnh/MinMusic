package com.metrolist.core.api.eq

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** Equalizer: the `eq.*` commands (api.md 3.8) and the `eq` topic. */
object EqModule : ApiModule {
    override val handlers: Map<String, Handler> = EqApi.handlers
    override val topics: Map<String, Topic> = mapOf("eq" to EqApi.eqTopic)
}
