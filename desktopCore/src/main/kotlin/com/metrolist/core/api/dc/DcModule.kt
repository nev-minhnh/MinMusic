package com.metrolist.core.api.dc

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** Device Connect (api.md 3.14): the `dc.*` methods and the `dc` topic. */
object DcModule : ApiModule {
    override val handlers: Map<String, Handler> = DcApi.handlers
    override val topics: Map<String, Topic> = mapOf("dc" to DcApi.dcTopic)
}
