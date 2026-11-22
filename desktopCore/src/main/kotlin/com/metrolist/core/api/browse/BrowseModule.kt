package com.metrolist.core.api.browse

import com.metrolist.core.api.ApiModule
import com.metrolist.core.api.HomeApi
import com.metrolist.core.server.Handler

/** `home.get` (HomeApi), `home.more`, `charts.get`, `browse.page`, `moods.list`. */
object BrowseModule : ApiModule {
    override val handlers: Map<String, Handler> = HomeApi.handlers + BrowseApi.handlers
}
