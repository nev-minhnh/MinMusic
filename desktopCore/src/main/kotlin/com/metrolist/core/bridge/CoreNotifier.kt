package com.metrolist.core.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A user-visible message raised by headless state code. [text] is already localised; [i18n] is an optional UI string key. */
data class UiMessage(
    val text: String,
    val i18n: String? = null,
    val level: String = "info",
)

/**
 * Replaces the v1 `AppState.snackbarHostState` for code that must not touch Compose. The v1 app collects [messages] into its
 * snackbar (desktopApp `DesktopCoreSeams`); the sidecar bridges them to `app.toast` events ([CoreBridge]).
 */
object CoreNotifier {
    private val flow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<UiMessage> = flow

    fun show(
        text: String,
        level: String = "info",
    ) {
        flow.tryEmit(UiMessage(text = text, level = level))
    }

    fun show(message: UiMessage) {
        flow.tryEmit(message)
    }

    /**
     * Seam: shows [message] with an action button and returns true when the action was pressed. The v1 app replaces it with
     * its snackbar (DesktopCoreSeams); the headless default only notifies and never reports the action.
     */
    @Volatile
    var showWithAction: suspend (message: String, actionLabel: String) -> Boolean =
        { message, _ ->
            show(message)
            false
        }
}

/** Sidecar-only wiring: [CoreNotifier] messages become `app.toast` events on [AppEvents]. */
object CoreBridge {
    private var installed = false

    @Synchronized
    fun install(scope: CoroutineScope) {
        if (installed) return
        installed = true
        scope.launch {
            CoreNotifier.messages.collect { message ->
                AppEvents.emit(
                    "app.toast",
                    buildJsonObject {
                        message.i18n?.let { put("i18n", it) }
                        put("text", message.text)
                        put("level", message.level)
                    },
                )
            }
        }
    }
}
