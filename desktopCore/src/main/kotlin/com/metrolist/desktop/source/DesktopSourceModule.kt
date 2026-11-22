package com.metrolist.desktop.source

import com.metrolist.shared.source.MusicSource
import com.metrolist.shared.source.nct.NctClient
import com.metrolist.shared.source.nct.NctSource
import com.metrolist.shared.source.soundcloud.SoundCloudClient
import com.metrolist.shared.source.soundcloud.SoundCloudSource
import com.metrolist.shared.source.zing.ZingClient
import com.metrolist.shared.source.zing.ZingSource
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Desktop twin of Android's `sourceModule`: clients and [MusicSource] adapters. The shared SourceRegistry (sharedModule)
 * collects every definition bound to MusicSource, which also turns on BestSourceSelector, the source lyrics providers
 * and the Zing/NCT/SoundCloud charts and search on desktop.
 */
val desktopSourceModule =
    module {
        single { DesktopZingCookieStore() }
        single { DesktopNctTokenStore() }

        single { SoundCloudClient() }
        single { ZingClient.create(credentials = get<DesktopZingCookieStore>()) }
        single { NctClient(get<DesktopNctTokenStore>()) }

        single { SoundCloudSource(get()) } bind MusicSource::class
        single { ZingSource(get()) } bind MusicSource::class
        single { NctSource(get()) } bind MusicSource::class
    }
