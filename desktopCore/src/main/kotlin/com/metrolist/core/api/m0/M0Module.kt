package com.metrolist.core.api.m0

import com.metrolist.core.api.ApiModule
import com.metrolist.core.api.SysApi
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** The M0 methods that stay where they are: sys and the `app` topic (the library entries moved to api/library/LibraryModule.kt in M1-2b). */
object M0Module : ApiModule {
    override val handlers: Map<String, Handler> = SysApi.handlers
    override val topics: Map<String, Topic> = mapOf("app" to SysApi.appTopic)
}
