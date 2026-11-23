package com.metrolist.core.api.importfiles

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** api.md 3.4 `import.files`. */
object ImportFilesModule : ApiModule {
    override val handlers: Map<String, Handler> = ImportFilesApi.handlers
}
