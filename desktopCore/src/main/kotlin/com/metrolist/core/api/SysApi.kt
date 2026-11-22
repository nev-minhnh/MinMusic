package com.metrolist.core.api

import com.metrolist.core.CoreConfig
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic
import com.metrolist.core.server.epochNow
import com.metrolist.core.server.handler
import com.metrolist.core.server.topic
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** api.md 3.1: `sys.ping`, `app.info`; the `app` topic has a snapshot only in M0. */
object SysApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "sys.ping" to handler { _, _ -> buildJsonObject { put("t", epochNow()) } },
            "app.info" to
                handler { _, _ ->
                    buildJsonObject {
                        put("version", BuildConfig.APP_VERSION)
                        put("core", CoreConfig.VERSION)
                        put("protocol", CoreConfig.PROTOCOL)
                        put("buildTime", "")
                        put(
                            "dataDirs",
                            buildJsonObject {
                                put("data", XdgUtils.getXdgDataHome().resolve("metrolist").absolutePath)
                                put("config", XdgUtils.getXdgConfigHome().resolve("metrolist").absolutePath)
                                put("state", XdgUtils.getXdgStateHome().resolve("metrolist").absolutePath)
                                put("cache", XdgUtils.getXdgCacheHome().resolve("metrolist").absolutePath)
                                put("logs", CoreConfig.logDirectory.absolutePath)
                            },
                        )
                        put("portable", false)
                    }
                },
        )

    val appTopic: Topic =
        topic { _, emit ->
            emit("app.snapshot", buildJsonObject { put("toasts", JsonArray(emptyList())) })
            awaitCancellation()
        }
}
