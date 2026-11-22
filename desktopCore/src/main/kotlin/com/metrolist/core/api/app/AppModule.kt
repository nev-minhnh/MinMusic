package com.metrolist.core.api.app

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** `app.openUrl`, `app.copyLink` (the rest of the `app.*` methods stay in SysApi). */
object AppModule : ApiModule {
    override val handlers: Map<String, Handler> = AppApi.handlers
}
