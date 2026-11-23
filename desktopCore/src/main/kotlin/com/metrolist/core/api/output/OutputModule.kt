package com.metrolist.core.api.output

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** Audio output devices (`player.output.*`). */
object OutputModule : ApiModule {
    override val handlers: Map<String, Handler> = OutputApi.handlers
}
