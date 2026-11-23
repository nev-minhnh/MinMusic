package com.metrolist.core.api.settings

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** `settings.schema/get/set/reset`, `lyrics.ai.setKey/test` and the `settings` topic. */
object SettingsModule : ApiModule {
    override val handlers: Map<String, Handler> = SettingsApi.handlers
    override val topics: Map<String, Topic> = mapOf("settings" to SettingsApi.settingsTopic)
}
