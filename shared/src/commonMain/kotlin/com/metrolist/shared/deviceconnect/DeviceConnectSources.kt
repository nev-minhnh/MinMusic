package com.metrolist.shared.deviceconnect

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.device_connect_source_disabled
import com.metrolist.shared.generated.resources.source_nhaccuatui
import com.metrolist.shared.generated.resources.source_soundcloud
import com.metrolist.shared.generated.resources.source_youtube_music
import com.metrolist.shared.generated.resources.source_zing_mp3
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.isForeignSourceId
import com.metrolist.shared.source.isSourceEnabled
import com.metrolist.shared.source.sourceOf
import org.jetbrains.compose.resources.getString

/**
 * Songs another device can stream by itself: YouTube Music and the other catalogues (SoundCloud, Zing MP3,
 * NhacCuaTui), which the target resolves through its own sources. Local files stay on their device.
 */
val SongItem.isDeviceConnectShareable: Boolean
    get() = !isLocal && (isYouTube || isForeignSourceId(id))

/** The source of [song] when it is turned off on this device, so this device cannot stream the song. */
fun deviceConnectDisabledSource(song: SongItem): SourceId? {
    if (song.isLocal || !isForeignSourceId(song.id)) return null
    return sourceOf(song.id).takeIf { !Settings.shared.isSourceEnabled(it) }
}

/** Explains that [source] is off on this device and how to turn it on. */
suspend fun deviceConnectDisabledSourceMessage(source: SourceId): String {
    val name =
        getString(
            when (source) {
                SourceId.SC -> Res.string.source_soundcloud
                SourceId.ZING -> Res.string.source_zing_mp3
                SourceId.NCT -> Res.string.source_nhaccuatui
                SourceId.YT, SourceId.LOCAL -> Res.string.source_youtube_music
            },
        )
    return getString(Res.string.device_connect_source_disabled, name)
}
