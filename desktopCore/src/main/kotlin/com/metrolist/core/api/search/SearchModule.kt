package com.metrolist.core.api.search

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** `search.suggest`, `search.run`, `search.history.list`, `search.history.remove`. */
object SearchModule : ApiModule {
    override val handlers: Map<String, Handler> = SearchApi.handlers
}
