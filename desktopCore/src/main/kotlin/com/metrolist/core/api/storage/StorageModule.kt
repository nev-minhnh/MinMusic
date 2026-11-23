package com.metrolist.core.api.storage

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** api.md 3.9 storage: `storage.usage`, `storage.clear`. */
object StorageModule : ApiModule {
    override val handlers: Map<String, Handler> = StorageApi.handlers
}
