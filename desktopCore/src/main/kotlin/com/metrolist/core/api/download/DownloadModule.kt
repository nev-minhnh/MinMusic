package com.metrolist.core.api.download

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** api.md 3.9 downloads: the `download.*` methods and the `downloads` topic. Storage and import live in their own modules. */
object DownloadModule : ApiModule {
    override val handlers: Map<String, Handler> = DownloadApi.handlers
    override val topics: Map<String, Topic> = mapOf("downloads" to DownloadApi.downloadsTopic)
}
