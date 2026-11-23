package com.metrolist.core

import com.metrolist.core.api.onboarding.OnboardingModule
import com.metrolist.core.api.onboarding.OnboardingRule
import com.metrolist.core.bridge.CoreBridge
import com.metrolist.desktop.backup.DesktopBackupRestoreManager
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.di.headlessStateModule
import com.metrolist.desktop.state.DesktopLocaleManager
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.db.DatabaseDowngradeException
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.discord.DiscordRpcManager
import com.metrolist.shared.discord.observeDiscordInitialization
import com.metrolist.shared.playback.PlaybackClientStats
import com.metrolist.shared.scrobble.ScrobbleSessions
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.flushDesktopCredentialWarnings
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.state.applyContentRegion
import com.metrolist.shared.state.coreModule
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.youtube.di.sharedModule
import com.metrolist.shared.youtube.playback.DesktopTokenProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.io.File
import java.nio.file.Paths

object CoreLog {
    fun info(message: String) {
        System.err.println("[core] $message")
    }

    fun error(
        message: String,
        error: Throwable? = null,
    ) {
        System.err.println("[core] ERROR $message")
        error?.printStackTrace()
    }
}

/** Per-launch secrets from the first stdin line plus the parsed command line. */
object CoreConfig {
    init {
        // M4-3a portable mode. This runs on the first access (SidecarMain assigns `options` right at the start), i.e. before
        // SidecarMain applies `--data-root`, which therefore still wins over the portable root.
        val portableFlag = System.getenv(PortableMode.ENV)
        if (portableFlag?.trim() == "1") {
            PortableMode.dataRoot(portableFlag, System.getProperty("minmusic.dataRoot"), ProcessHandle.current().info().command().orElse(null))
                ?.let { System.setProperty("minmusic.dataRoot", it) }
        }
    }

    const val VERSION = "0.1.0"
    const val PROTOCOL = 1

    @Volatile
    var options: CoreOptions = CoreOptions()

    @Volatile
    var token: String = ""

    @Volatile
    var artToken: String = ""

    val corePidFile: File get() = File(XdgUtils.getXdgStateHome(), "metrolist/core.pid")

    val logDirectory: File get() = options.logDir?.let(::File) ?: File(XdgUtils.getXdgStateHome(), "metrolist/logs")
}

/**
 * Portable mode (docs/architecture.md 7): with `MINMUSIC_PORTABLE=1` and no `--data-root` the XDG base is `<app>/data`,
 * where the core runs as `<app>/sidecar/runtime/bin/minmusic-core.exe` (that is `<runtime image dir>/../../data`).
 */
object PortableMode {
    const val ENV = "MINMUSIC_PORTABLE"

    /** The data root to use, or null when portable mode is off, a root is already set, or the executable path is unusable. */
    fun dataRoot(
        portableFlag: String?,
        existingRoot: String?,
        command: String?,
    ): String? {
        if (portableFlag?.trim() != "1" || !existingRoot.isNullOrBlank()) return null
        val executable = command?.takeIf(String::isNotBlank)?.let { runCatching { Paths.get(it) }.getOrNull() } ?: return null
        val runtimeImage = executable.parent?.parent ?: return null // <app>/sidecar/runtime
        val app = runtimeImage.parent?.parent ?: return null
        return app.resolve("data").normalize().toString()
    }
}

/**
 * The v1 `initializeBackend()` steps of Main.kt without any window, tray, taskbar or WebView. Runs once on an IO thread;
 * the API handlers await [ready] before they touch the database or PlayerState.
 */
object CoreBackend {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ready = CompletableDeferred<Unit>()

    @Volatile
    var initialized = false
        private set

    @Volatile
    var failure: Throwable? = null
        private set

    @Volatile
    var initMillis: Long = -1
        private set

    fun start() {
        CoreBridge.install(scope)
        scope.launch(Dispatchers.IO) {
            val begin = System.nanoTime()
            try {
                init()
                initMillis = (System.nanoTime() - begin) / 1_000_000
                CoreLog.info("backend initialised in $initMillis ms")
                ready.complete(Unit)
            } catch (error: Throwable) {
                failure = error
                CoreLog.error("backend initialisation failed", error)
                ready.completeExceptionally(error)
            }
        }
    }

    suspend fun await() = ready.await()

    private fun init() {
        // M4-3a: captured before the pending restore and before the database opens (v1 Main.kt:895-901).
        val dbExistedAtStart = OnboardingRule.databaseFile(XdgUtils.getXdgDataHome()).isFile
        // Signature: DesktopBackupRestoreManager.applyPendingRestore(settings: Settings): Boolean
        DesktopBackupRestoreManager.applyPendingRestore(Settings.shared)
        OnboardingModule.initialize(Settings.shared, dbExistedAtStart)
        val settings = Settings.shared
        // MinMusic sends no telemetry; drop any crash-report consent carried in by a restored backup.
        if (settings.sentryEnabled) settings.sentryEnabled = false
        if (settings.desktopPendingOnboardingSentryConsent.isNotBlank()) settings.desktopPendingOnboardingSentryConsent = ""
        DesktopLocaleManager.apply(settings.appLanguage)

        // Initialise logging only after settings migration to avoid java.util.prefs/JUL class-init deadlocks.
        AppLogger.initialize(scope, CoreConfig.logDirectory)
        AppLogger.setEnabled(settings.enableLogging)
        flushDesktopCredentialWarnings()
        AppLogger.info("Core", "MinMusic core ${CoreConfig.VERSION} starting (app ${BuildConfig.APP_VERSION})")

        startKoin {
            modules(coreModule, sharedModule, headlessStateModule { DesktopTokenProvider() })
        }
        val database =
            try {
                GlobalContext.get().get<MusicDatabase>()
            } catch (error: Exception) {
                val downgrade =
                    generateSequence<Throwable>(error) { it.cause }.filterIsInstance<DatabaseDowngradeException>().firstOrNull()
                if (downgrade != null) CoreLog.error(downgrade.message.orEmpty())
                throw error
            }
        runBlocking { SongLikeState.recoverDatabaseLikes(database) }
        SongMetadataState.bind(database, scope)

        LastFM.initialize(apiKey = BuildConfig.LASTFM_API_KEY, secret = BuildConfig.LASTFM_SECRET)
        LastFM.sessionKey = settings.lastFmSession.ifBlank { null }
        ScrobbleSessions.restorePersistedAccounts()

        GlobalYouTube.applyContentRegion(
            contentCountry = settings.contentCountry,
            contentLanguage = settings.contentLanguage,
            systemLocale = DesktopLocaleManager.systemLocale,
        )

        // Singleton construction scans storage and creates clients; none of these may touch a window.
        SongCacheManager.cacheDir
        PlayerState.player
        initialized = true
        LibraryState.downloadStates
        SongLikeState.likedSongIds
        LyricsState.currentLyricsEntries
        ListenTogetherState.initialize()

        fetchAnonymousVisitorData(settings)
        AuthState.loadSession()
        scope.launch(Dispatchers.Default) {
            if (!settings.credentialsPending) DesktopDeviceConnect.manager.restoreIfEnabled(Unit)
        }
        scope.launch(Dispatchers.IO) { PlaybackClientStats.takeReportOnLaunch() }
        scope.launch(Dispatchers.Main) { observeDiscordInitialization { DiscordRpcManager.init(GlobalYouTube.client) } }
    }

    private fun fetchAnonymousVisitorData(settings: Settings) {
        if (settings.credentialsPending || settings.innerTubeCookie.isNotBlank() || settings.visitorData.isNotBlank()) return
        scope.launch(Dispatchers.IO) {
            val visitorData =
                try {
                    GlobalInnerTube.instance.fetchFreshVisitorData()
                } catch (error: Exception) {
                    AppLogger.warning("Core", "Visitor data fetch failed", error)
                    null
                }
            if (visitorData.isNullOrBlank()) return@launch
            if (settings.credentialsPending || settings.innerTubeCookie.isNotBlank() || settings.visitorData.isNotBlank()) return@launch
            settings.visitorData = visitorData
            GlobalYouTube.updateSession(
                cookie = settings.innerTubeCookie,
                visitorData = visitorData,
                dataSyncId = settings.dataSyncId,
                authUser = settings.innerTubeAuthUser,
                useLoginForBrowse = false,
            )
            AppLogger.info("Core", "Visitor data fetched")
        }
    }
}
