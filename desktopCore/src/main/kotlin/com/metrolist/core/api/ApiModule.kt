package com.metrolist.core.api

import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/**
 * One module per API area, a Kotlin `object` named `<Area>Module` in `com.metrolist.core.api.<area>`.
 * [ApiRegistry] discovers every such object at start, so nothing else needs editing to register an area.
 */
interface ApiModule {
    val handlers: Map<String, Handler>
    val topics: Map<String, Topic> get() = emptyMap()
}
