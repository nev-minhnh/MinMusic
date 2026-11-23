package com.metrolist.core.api.output

import com.metrolist.core.api.PlayerApi
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.desktop.state.playback.PlayerState as DesktopPlayerState
import com.metrolist.shared.equalizer.EqualizerOutputDevice
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** api.md 3.6 `player.output.list` / `player.output.set`. */
object OutputApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "player.output.list" to handler { _, _ -> list() },
            "player.output.set" to handler { params, _ -> set(params.requireStr("id")) },
        )

    private suspend fun list(): JsonElement {
        backendReady()
        PlayerApi.ensureCoreDefaults()
        val names = withContext(Dispatchers.IO) { playbackMixerNames() }
        val devices = outputDevices(names)
        return buildJsonObject {
            put(
                "devices",
                JsonArray(
                    devices.map { d ->
                        buildJsonObject {
                            put("id", d.id)
                            put("name", d.name)
                            put("kind", d.kind)
                            put("default", d.default)
                        }
                    },
                ),
            )
            put("current", currentOutputId(names, OutputPrefs.chosen) ?: "")
        }
    }

    /**
     * Stores the choice, routes the audio there from the next line open (a track change or a seek) and tells the
     * equalizer, which keeps one profile per output. Core-main: `MusicPlayer.setPreferredDevice(device: Any?)` and
     * `EqualizerProfiles.onOutputDeviceChanged(device: EqualizerOutputDevice?)`.
     */
    private suspend fun set(id: String): JsonElement {
        backendReady()
        PlayerApi.ensureCoreDefaults()
        val names = withContext(Dispatchers.IO) { playbackMixerNames() }
        if (id !in names) throw ApiException("not_found", "unknown output device")
        OutputPrefs.set(id)
        // The process-wide preference is set directly too: the renderer of the current track may not exist yet.
        AudioRenderer.setPreferredOutputDevice(id)
        withContext(Dispatchers.Main) {
            DesktopPlayerState.player.setPreferredDevice(id)
            EqualizerProfiles.onOutputDeviceChanged(EqualizerOutputDevice(key = id, kind = equalizerKindOf(outputKindOf(id)), productName = id))
        }
        return emptyResult()
    }
}
