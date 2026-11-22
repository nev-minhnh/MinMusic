package com.metrolist.core

import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.desktop.utils.DesktopSingleInstance
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.settings.flushDesktopSettings
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Ordered graceful shutdown (architecture 3.3): persist the queue, pause downloads, flush settings, close the DB,
 * stop Device Connect / Listen Together, delete core.pid, exit 0. Hard cap 2.5 s, then Runtime.halt(0).
 */
object CoreShutdown {
    const val HARD_CAP_MS = 2_500L

    private val started = AtomicBoolean(false)

    @Volatile
    var onBeforeExit: (() -> Unit)? = null

    fun run(
        reason: String,
        hardCap: Boolean = true,
    ) {
        if (!started.compareAndSet(false, true)) return
        val begin = System.nanoTime()
        CoreLog.info("shutdown requested: $reason")
        if (hardCap) {
            Thread({
                runCatching { Thread.sleep(HARD_CAP_MS) }
                CoreLog.error("shutdown exceeded $HARD_CAP_MS ms, halting")
                Runtime.getRuntime().halt(0)
            }, "CoreShutdownWatchdog").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
        Thread({
            try {
                steps()
            } catch (error: Throwable) {
                CoreLog.error("shutdown step failed", error)
            }
            CoreLog.info("shutdown done in ${(System.nanoTime() - begin) / 1_000_000} ms")
            exitProcess(0)
        }, "CoreShutdown").start()
    }

    private fun steps() {
        runBlocking {
            if (CoreBackend.initialized) {
                // PlayerState.release() persists the queue and position (PlaybackStatePersistence), closes history and
                // stops Listen Together.
                runCatching { DiscordRpcManager.destroy() }
                runCatching { PlayerState.release() }
                runCatching { DownloadManager.release() }
                runCatching { SongCacheManager.release() }
                runCatching { PlayerState.player.release() }
            }
            runCatching { flushDesktopSettings() }
            if (CoreBackend.initialized) {
                runCatching { GlobalContext.getOrNull()?.get<MusicDatabase>()?.close() }
                runCatching { DesktopDeviceConnect.manager.release() }
            }
            runCatching { stopKoin() }
        }
        runCatching { CoreConfig.corePidFile.delete() }
        runCatching { DesktopSingleInstance.release() }
        runCatching { AppLogger.shutdown() }
        onBeforeExit?.invoke()
    }
}
