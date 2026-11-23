package com.metrolist.core.api.account

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * "Something changed" signals for the `account` topic. The topic recomputes the payload when it sees a signal, so any
 * handler (accounts, sources, integrations) only has to call [accountChanged] or [sourcesChanged] after a mutation.
 */
object AccountBus {
    private val account = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val sources = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val accountSignals: SharedFlow<Unit> = account
    val sourcesSignals: SharedFlow<Unit> = sources

    fun accountChanged() {
        account.tryEmit(Unit)
    }

    fun sourcesChanged() {
        sources.tryEmit(Unit)
    }
}
