package com.metrolist.core.api.backup

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** `backup.create`, `backup.inspect`, `backup.restore`, `import.foreignBackup` (M4-3a). */
object BackupModule : ApiModule {
    override val handlers: Map<String, Handler> = BackupApi.handlers
}
