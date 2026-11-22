package com.metrolist.core

import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless twin of Compose's GlobalSnapshotManager: a global write schedules one coalesced
 * Snapshot.sendApplyNotifications() on core-main. Without it snapshotFlow never emits.
 */
object SnapshotPump {
    private val installed = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        Snapshot.registerGlobalWriteObserver {
            if (pending.compareAndSet(false, true)) {
                CoreMain.execute {
                    pending.set(false)
                    Snapshot.sendApplyNotifications()
                }
            }
        }
    }
}
