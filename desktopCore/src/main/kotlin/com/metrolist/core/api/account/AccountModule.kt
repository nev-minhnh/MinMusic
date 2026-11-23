package com.metrolist.core.api.account

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** `account.*` and the `account` topic (snapshot `account.get` + `sources.list`; deltas `account.changed`, `sources.changed`). */
object AccountModule : ApiModule {
    override val handlers: Map<String, Handler> = AccountApi.handlers
    override val topics: Map<String, Topic> = mapOf("account" to AccountApi.accountTopic)
}
