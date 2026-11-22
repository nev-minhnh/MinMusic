package com.metrolist.core.api.m0

import com.metrolist.core.api.ApiModule
import com.metrolist.core.api.LibraryApi
import com.metrolist.core.api.SysApi
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** The M0 methods that stay where they are: sys/app and the library subset. M1-2b moves the library entries out in wave 2. */
object M0Module : ApiModule {
    override val handlers: Map<String, Handler> = SysApi.handlers + LibraryApi.handlers
    override val topics: Map<String, Topic> = mapOf("app" to SysApi.appTopic)
}
