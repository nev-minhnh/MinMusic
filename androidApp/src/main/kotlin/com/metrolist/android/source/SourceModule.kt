package com.metrolist.android.source

import com.metrolist.android.source.credentials.AndroidNctTokenStore
import com.metrolist.android.source.credentials.ZingCookieStore
import com.metrolist.shared.source.MusicSource
import com.metrolist.shared.source.nct.NctClient
import com.metrolist.shared.source.nct.NctSource
import com.metrolist.shared.source.soundcloud.SoundCloudClient
import com.metrolist.shared.source.soundcloud.SoundCloudSource
import com.metrolist.shared.source.zing.ZingClient
import com.metrolist.shared.source.zing.ZingSource
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

/** Clients and [MusicSource] adapters; the shared SourceRegistry collects every definition bound to MusicSource. */
val sourceModule =
    module {
        single { ZingCookieStore() }
        single { AndroidNctTokenStore() }

        single { SoundCloudClient() }
        single { ZingClient.create(credentials = get<ZingCookieStore>()) }
        single { NctClient(get<AndroidNctTokenStore>()) }

        single { SoundCloudSource(get()) } bind MusicSource::class
        single { ZingSource(get()) } bind MusicSource::class
        single { NctSource(get()) } bind MusicSource::class
    }
