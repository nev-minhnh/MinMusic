# M1-1 checklist (for the wave-1 integration agent)

No Gradle was run. Compile errors, if any, are most likely in the spots marked RISK.

## 1. Moved files (git mv, packages unchanged): old -> new
Old root: desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/   New root: desktopCore/src/main/kotlin/com/metrolist/desktop/
```
DesktopStartupTasks.kt -> DesktopStartupTasks.kt
auth/DesktopDiscordLogin.kt -> auth/DesktopDiscordLogin.kt
auth/DesktopDiscordRegistration.kt -> auth/DesktopDiscordRegistration.kt
auth/DiscordLoginCallback.kt -> auth/DiscordLoginCallback.kt
auth/DiscordLoginNavigation.kt -> auth/DiscordLoginNavigation.kt
auth/WindowsLoginHelper.kt -> auth/WindowsLoginHelper.kt
backup/DesktopBackupRestoreManager.kt -> backup/DesktopBackupRestoreManager.kt
backup/PrivateBackupFile.kt -> backup/PrivateBackupFile.kt
crash/DesktopCrashPrompt.kt -> crash/DesktopCrashPrompt.kt
crash/DesktopCrashRecovery.kt -> crash/DesktopCrashRecovery.kt
deviceconnect/DesktopDeviceConnect.kt -> deviceconnect/DesktopDeviceConnect.kt
di/AppModule.kt -> di/AppModule.kt
history/HistoryRepository.kt -> history/HistoryRepository.kt
listentogether/DesktopListenTogetherPlayback.kt -> listentogether/DesktopListenTogetherPlayback.kt
playback/PlaybackErrorReporter.kt -> playback/PlaybackErrorReporter.kt
source/DesktopSourceCredentials.kt -> source/DesktopSourceCredentials.kt
source/DesktopSourceLogins.kt -> source/DesktopSourceLogins.kt
source/DesktopSourceModule.kt -> source/DesktopSourceModule.kt
state/DesktopLocaleManager.kt -> state/DesktopLocaleManager.kt
state/app/AppState.kt -> state/app/AppState.kt
state/app/DesktopNavigation.kt -> state/app/DesktopNavigation.kt
state/app/PlayerPanelState.kt -> state/app/PlayerPanelState.kt
state/app/PlayerPresentationState.kt -> state/app/PlayerPresentationState.kt
state/app/UiState.kt -> state/app/UiState.kt
state/auth/AuthState.kt -> state/auth/AuthState.kt
state/content/ArtistState.kt -> state/content/ArtistState.kt
state/content/BrowseState.kt -> state/content/BrowseState.kt
state/content/DesktopSearchLink.kt -> state/content/DesktopSearchLink.kt
state/content/HomeState.kt -> state/content/HomeState.kt
state/content/LibraryContentState.kt -> state/content/LibraryContentState.kt
state/content/LibraryState.kt -> state/content/LibraryState.kt
state/content/PlaylistAlbumState.kt -> state/content/PlaylistAlbumState.kt
state/content/PlaylistCompletion.kt -> state/content/PlaylistCompletion.kt
state/content/PlaylistRenameRequests.kt -> state/content/PlaylistRenameRequests.kt
state/content/PlaylistSongRemoval.kt -> state/content/PlaylistSongRemoval.kt
state/content/PlaylistSongReorder.kt -> state/content/PlaylistSongReorder.kt
state/content/PodcastState.kt -> state/content/PodcastState.kt
state/content/SearchState.kt -> state/content/SearchState.kt
state/history/HistoryScreenState.kt -> state/history/HistoryScreenState.kt
state/playback/DesktopCastPlayback.kt -> state/playback/DesktopCastPlayback.kt
state/playback/DesktopDeviceConnectSession.kt -> state/playback/DesktopDeviceConnectSession.kt
state/playback/DesktopLyricsOffsetWrites.kt -> state/playback/DesktopLyricsOffsetWrites.kt
state/playback/DesktopMediaControls.kt -> state/playback/DesktopMediaControls.kt
state/playback/DesktopPlaybackController.kt -> state/playback/DesktopPlaybackController.kt
state/playback/DesktopPlaybackHistory.kt -> state/playback/DesktopPlaybackHistory.kt
state/playback/DesktopPlaybackLibrary.kt -> state/playback/DesktopPlaybackLibrary.kt
state/playback/DesktopPlaybackResourceSelection.kt -> state/playback/DesktopPlaybackResourceSelection.kt
state/playback/DesktopPlaybackResume.kt -> state/playback/DesktopPlaybackResume.kt
state/playback/DesktopPlaybackTransitions.kt -> state/playback/DesktopPlaybackTransitions.kt
state/playback/DesktopQueueRadio.kt -> state/playback/DesktopQueueRadio.kt
state/playback/DesktopQueueTransitions.kt -> state/playback/DesktopQueueTransitions.kt
state/playback/DesktopRadioPlayback.kt -> state/playback/DesktopRadioPlayback.kt
state/playback/DesktopRelatedSongs.kt -> state/playback/DesktopRelatedSongs.kt
state/playback/DesktopSleepTimerCastVolume.kt -> state/playback/DesktopSleepTimerCastVolume.kt
state/playback/DesktopVideoLifecycle.kt -> state/playback/DesktopVideoLifecycle.kt
state/playback/DiscordRpcState.kt -> state/playback/DiscordRpcState.kt
state/playback/ListenTogetherState.kt -> state/playback/ListenTogetherState.kt
state/playback/LyricsState.kt -> state/playback/LyricsState.kt
state/playback/PersistedPlaybackState.kt -> state/playback/PersistedPlaybackState.kt
state/playback/PlaybackQueuePolicy.kt -> state/playback/PlaybackQueuePolicy.kt
state/playback/PlaybackStatePersistence.kt -> state/playback/PlaybackStatePersistence.kt
state/playback/PlayerState.kt -> state/playback/PlayerState.kt
state/playback/SabrCacheReadiness.kt -> state/playback/SabrCacheReadiness.kt
state/playback/SleepTimerDeadline.kt -> state/playback/SleepTimerDeadline.kt
state/playback/SuggestedSongKeys.kt -> state/playback/SuggestedSongKeys.kt
storage/AudioDownloadRemuxer.kt -> storage/AudioDownloadRemuxer.kt
storage/AudioDownloadTranscoder.kt -> storage/AudioDownloadTranscoder.kt
storage/CacheAccessTimes.kt -> storage/CacheAccessTimes.kt
storage/CacheMaintenance.kt -> storage/CacheMaintenance.kt
storage/DownloadArtwork.kt -> storage/DownloadArtwork.kt
storage/DownloadAudioTags.kt -> storage/DownloadAudioTags.kt
storage/DownloadDirectories.kt -> storage/DownloadDirectories.kt
storage/DownloadLyricsEnricher.kt -> storage/DownloadLyricsEnricher.kt
storage/DownloadManager.kt -> storage/DownloadManager.kt
storage/DownloadMetadataStore.kt -> storage/DownloadMetadataStore.kt
storage/DownloadQueueSummary.kt -> storage/DownloadQueueSummary.kt
storage/SongCacheManager.kt -> storage/SongCacheManager.kt
storage/SongOfflineSnapshot.kt -> storage/SongOfflineSnapshot.kt
storage/StoredAudioHistory.kt -> storage/StoredAudioHistory.kt
update/DesktopUpdateManager.kt -> update/DesktopUpdateManager.kt
utils/AppLogger.kt -> utils/AppLogger.kt
utils/DesktopRelaunch.kt -> utils/DesktopRelaunch.kt
utils/DesktopSingleInstance.kt -> utils/DesktopSingleInstance.kt
utils/DesktopUrlHandler.kt -> utils/DesktopUrlHandler.kt
utils/NativeMemoryTrimmer.kt -> utils/NativeMemoryTrimmer.kt
utils/WindowsAppIdentity.kt -> utils/WindowsAppIdentity.kt
TEST backup/DesktopBackupRestoreManagerTest.kt -> TEST backup/DesktopBackupRestoreManagerTest.kt
TEST backup/LegacyBackupSqlTest.kt -> TEST backup/LegacyBackupSqlTest.kt
TEST history/HistoryRoomImportTest.kt -> TEST history/HistoryRoomImportTest.kt
TEST history/LegacyHistoryMigrationTest.kt -> TEST history/LegacyHistoryMigrationTest.kt
desktopApp/src/jvmTest/resources/backup/android-v1.sql -> desktopCore/src/test/resources/backup/android-v1.sql
state/playback/DesktopAnimatedArtwork.kt (moved in a later commit, headless: only compose.runtime + AppState)
```

## 2. `internal` members opened up (now public, same names)
DesktopDiscordLogin (object), DesktopDiscordLoginFlow, DiscordLoginNavigation, PlaylistAdditionStopped, SuggestedSongKeys, WindowsAppIdentity, PlayerPanelState, PlayerPresentationState (+ UiState.playerPanels / playerPresentation / videoFullscreenPanels), PlayerState.currentMediaPositionMs / mediaControls / mediaPositionDiscontinuities / setVideoWindowVisible / volumeState, validatedLocalMediaUrl, DesktopMediaControls (data class), DownloadManager.deleteDownloadsAndAwait, playlistDownloadState, playlistDownloadProgress, desktopRestartCommand() (no-arg overload), ProcessBuilder.prepareDesktopRelaunch, desktopSongOfflineAvailability, DownloadQueueSummary (class + downloadQueueSummary), launchVisitorDataFetch, readLogPreview, readWindowsLoginHelperOutput, registerDesktopDiscordCallback, DiscordRpcState.updateDiscordRpc, AppState.scope.
Also internal -> internal but reachable by test only: DownloadArtwork.encode (was private).
RISK: a name collision hid one more `internal` use (found by name scan, not by compiler). Fix = drop `internal`.

## 3. Seams (core declaration <-> desktopApp registration)
| Core declaration | desktopApp side |
|---|---|
| `core/bridge/CoreNotifier` (`show`, `showWithAction`, `messages`) | `state/app/DesktopCoreSeams.install()`: collects `messages` into `snackbarHostState`; sets `showWithAction`. UI keeps `AppState.snackbarHostState` through the extension `val AppState.snackbarHostState` (same file); 12 UI files got `import ...state.app.snackbarHostState` |
| `core/bridge/ArtworkCache.evict` | `DesktopCoreSeams.install()` -> `DesktopImageLoader.evictArtwork` (HomeState.refetchSongMetadata) |
| `ArtworkCache.onTrackArtwork` (PlayerState.updateCurrentTrack) | `PlayerArtworkPreviews.current` in `state/playback/PlayerArtworkPreview.kt`; extension `PlayerState.artworkPreview` (6 UI files import it) |
| `WindowHost.visible` (PlayerState network monitor) | `DesktopCoreSeams.install()` mirrors `ComposeTrayManager.windowVisible` |
| `WindowHost.showWindow` (DesktopUrlHandler) | `ComposeTrayManager.showWindow()` |
| `WindowHost.playbackVisibility` (PlayerState.playbackUiVisible) | `window/playbackWindowVisibility()` |
| `CrashHooks.crashHintInstaller` (DesktopCrashRecovery) | `installCrashScreenshotHint` |
| `HomeState.saveListScroll/restoreListScroll` (ints) | `state/content/HomeListState.kt`: `@Composable HomeState.rememberListState(key)`; 4 UI files import it |
| `UiState` without colours | `state/app/UiColors.kt` (`seedColor`, `artworkSeedColor`); Main, Theme, Components, LyricsSideSheet use it |
| `di/AppModule.kt` = `headlessStateModule` only | `di/ComposeStateModule.kt` = `composeStateModule` (same package, Main import unchanged) |
| `CoreMainDispatcherFactory.loadPriority` (property `minmusic.sidecar`) | v1 leaves it unset -> Swing wins; `-Dminmusic.sidecar=true` added to `sidecarJvmArgs` |
| `AppEvents` -> `SysApi.appTopic`; `CoreBridge.install(scope)` in `CoreBackend.start()` turns CoreNotifier into `app.toast {text, level, i18n?}` | n/a |
| Main.kt: `DesktopCoreSeams.install()` as first statement after `startup.trace("main")` | |

Deviations from the brief table: `UiState` itself (all flags) moved to core under the same FQN, so no `UiFlags` indirection; `DesktopAnimatedArtwork` moved to core (it has no Compose UI); `AppState`/`DesktopNavigation`/`PlayerPanelState`/`PlayerPresentationState` moved with it.

## 4. Gradle blocks changed
- `desktopCore/build.gradle.kts`: plugin `java-library` + `org.jetbrains.kotlin.plugin.compose` (ListenTogetherState has one @Composable); removed `evaluationDependsOn(":desktopApp")` and `implementation(project(":desktopApp"))`; added `platformSqliteJdbc` task (+ platform vals, copied from desktopApp); `api(shared, runtime, components.resources)`; added implementation deps okhttp, datastore, jna, jna.platform, ktor client cio/websockets/content-negotiation/serialization-json, sharesheet, nucleus.core-runtime, `files(platformSqliteJdbc)`; test deps (kotlin-test, junit4, settings-test, ktor mock, javacv, coroutines-test) + `tasks.test { useJUnit() }`; new `runtimeClasspath`-only exclude block (Skiko, compose ui/foundation/material/material3/adaptive/animation/desktop, coil-compose*, kdroidfilter, mikepenz, shimmer, reorderable, swipe, navigation3-ui, navigationevent-compose, lifecycle-*-compose, savedstate-compose); `-Dminmusic.sidecar=true` in `sidecarJvmArgs`; jlink `--include-locales=en,vi`; sidecarImage uses the local `platformSqliteJdbc`.
- `desktopApp/build.gradle.kts`: `evaluationDependsOn(":desktopCore")`; `api(project(":desktopCore"))` in jvmMain; `platformSqliteJdbc` = `project(":desktopCore").tasks.named(...)` (task, ArchiveOps, sqlite source config, platformClassifier, sqliteNativePath removed).
- `shared/.../desktopMain/.../VideoPlayer.kt`: FrameBitmapHolder bitmaps are `lazy`.
RISKS: (1) `api(...)` needs java-library (added). (2) navigation3-runtime / navigationevent core are kept on purpose (`Screen : NavKey`); if the audit still shows a needed class missing, relax an exclude rather than adding a seam. (3) Missing compile deps in desktopCore: add the lib that desktopApp's jvmMain declared. (4) desktopCore sees `compose.runtime` types only; if a moved file needs `androidx.compose.ui.*` at compile time it was missed by the import scan.

## 5. Behaviour notes
- `DownloadArtwork.encode` uses ImageIO (JPEG, PNG, GIF, BMP). WebP covers decode to null (no embedded cover) - Skia used to read them.
- Toasts: protocol `AppToast` requires `i18n`; the core emits `{text, level}` (+ `i18n` only when given). Coordinator should relax the schema (`i18n?`, `text?`).
- Baseline m0 contract on the dac1f688 image with an empty test root: 9 pass / 20 fail (library, home, album and player tests need fixtures/data: `tests/fixtures/home.json` is not in origin/main). Compare after-image against that, or against a seeded root.
