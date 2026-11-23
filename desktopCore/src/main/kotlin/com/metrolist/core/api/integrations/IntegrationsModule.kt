package com.metrolist.core.api.integrations

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** `lastfm.*`, `listenbrainz.*`, `discord.*`. */
object IntegrationsModule : ApiModule {
    override val handlers: Map<String, Handler> = IntegrationsApi.handlers
}
