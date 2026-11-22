package com.metrolist.core.bridge

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement

/**
 * Process-wide event bus for the `app` topic (api.md section 4). Any core code may [emit]; `SysApi.appTopic` forwards
 * the events to every subscriber after its snapshot. Events emitted while nobody listens are dropped.
 */
object AppEvents {
    private val flow = MutableSharedFlow<Pair<String, JsonElement>>(extraBufferCapacity = 64)
    val events: SharedFlow<Pair<String, JsonElement>> = flow

    fun emit(
        event: String,
        payload: JsonElement,
    ) {
        flow.tryEmit(event to payload)
    }
}
