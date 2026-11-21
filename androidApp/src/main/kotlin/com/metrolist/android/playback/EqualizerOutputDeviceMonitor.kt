package com.metrolist.android.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.metrolist.shared.equalizer.EqualizerOutputDevice
import com.metrolist.shared.equalizer.EqualizerOutputKind
import com.metrolist.shared.equalizer.EqualizerProfiles

/** Reports the output media most likely plays on, so MetroEQ can switch to that device's profile. */
internal class EqualizerOutputDeviceMonitor(
    context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }

    fun start() {
        // The callback also reports the devices already connected.
        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun refresh() {
        val manager = audioManager ?: return
        // Media follows the most recently attached external route; rank route classes the same way.
        val device =
            manager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink && routePriority(it.type) > 0 }
                .maxByOrNull { routePriority(it.type) }
        EqualizerProfiles.onOutputDeviceChanged(device?.toEqualizerOutputDevice())
    }
}

private fun routePriority(type: Int): Int =
    when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> 4

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> 3

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_HDMI,
        -> 2

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1

        else -> 0
    }

private fun AudioDeviceInfo.toEqualizerOutputDevice(): EqualizerOutputDevice {
    val name = productName?.toString()?.trim().orEmpty()
    val (route, kind) =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER" to EqualizerOutputKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED" to EqualizerOutputKind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP" to EqualizerOutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET" to EqualizerOutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER" to EqualizerOutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID" to EqualizerOutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET" to EqualizerOutputKind.USB
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE" to EqualizerOutputKind.USB
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY" to EqualizerOutputKind.USB
            AudioDeviceInfo.TYPE_HDMI -> "HDMI" to EqualizerOutputKind.OTHER
            else -> "LINE" to EqualizerOutputKind.OTHER
        }
    // The speaker and wired jack report the phone model, which would not tell outputs apart.
    val named = kind != EqualizerOutputKind.SPEAKER && kind != EqualizerOutputKind.WIRED
    return EqualizerOutputDevice(
        key = if (named && name.isNotEmpty()) "$route:$name" else route,
        kind = kind,
        productName = if (named) name else "",
    )
}
