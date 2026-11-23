package com.metrolist.core.api.importlists

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** `import.providers`, `import.login`, `import.playlists`, `import.run` (api.md 3.12). */
object ImportListsModule : ApiModule {
    override val handlers: Map<String, Handler> = ImportListsApi.handlers
}
