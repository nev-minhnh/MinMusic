package com.metrolist.core.api.sources

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** `sources.*`, `source.zing.*`, `source.nct.*`. */
object SourcesModule : ApiModule {
    override val handlers: Map<String, Handler> = SourcesApi.handlers
}
