/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.di

import com.metrolist.desktop.source.desktopSourceModule
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.storage.desktopSongOfflineAvailability
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.OfflineSongAvailability
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.youtube.playback.LocalAudioSource
import org.koin.dsl.module

/**
 * The v1 app's `composeStateModule` (desktopApp `di/ComposeStateModule.kt`) minus the page-bound WebView runtime: no such
 * runtime exists in the headless core sidecar, so the caller supplies the [TokenProvider].
 */
fun headlessStateModule(tokenProvider: () -> TokenProvider) =
    module {
        includes(desktopSourceModule)
        single<MusicDatabase> { getDatabaseBuilder().build() }
        single<LocalAudioSource> {
            LocalAudioSource { id, song ->
                DownloadManager.getLocalPlaybackResource(id, song) ?: SongCacheManager.getCachedPlaybackResource(id, song)
            }
        }
        single<DatabaseDao> { get<MusicDatabase>().dao() }
        single<OfflineSongAvailability> { desktopSongOfflineAvailability(get()) }
        single<TokenProvider> { tokenProvider() }
    }
