package com.metrolist.core.api.crash

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.shared.crash.FileCrashStore
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * `crash.pending`, `crash.dismiss` (api.md 3.15). The store is the same directory DesktopCrashRecovery.store uses
 * (`<data>/metrolist/crashes`); it is opened directly so the core never loads the Swing crash dialog class.
 */
object CrashModule : ApiModule {
    private val directory get() = File(XdgUtils.getXdgDataHome(), "metrolist/crashes")
    private val store get() = FileCrashStore(directory)

    override val handlers: Map<String, Handler> =
        mapOf(
            "crash.pending" to
                handler { _, _ ->
                    withContext(Dispatchers.IO) {
                        // Signatures: FileCrashStore.pendingReportId(): String?, FileCrashStore.list(): List<CrashReport>
                        val id = store.pendingReportId()
                        val report = id?.let { wanted -> store.list().firstOrNull { it.id == wanted } }
                        if (report == null) {
                            emptyResult()
                        } else {
                            val dto = CrashSummary.of(report, File(directory, "${report.id}.json").path)
                            buildJsonObject {
                                put(
                                    "report",
                                    buildJsonObject {
                                        put("when", dto.whenMs)
                                        put("summary", dto.summary)
                                        put("path", dto.path)
                                    },
                                )
                            }
                        }
                    }
                },
            "crash.dismiss" to
                handler { _, _ ->
                    withContext(Dispatchers.IO) {
                        // Signature: FileCrashStore.acknowledge(id: String)
                        val crashStore = store
                        crashStore.pendingReportId()?.let(crashStore::acknowledge)
                        emptyResult()
                    }
                },
        )
}
