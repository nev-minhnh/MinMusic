package com.metrolist.core.api.tracksource

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** `track.sources`, `track.setSource`. */
object TrackSourceModule : ApiModule {
    override val handlers: Map<String, Handler> = TrackSourceApi.handlers
}
