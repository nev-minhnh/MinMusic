package com.metrolist.core.api.output

import com.metrolist.shared.equalizer.EqualizerOutputKind
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** One audio output as `player.output.list` reports it. */
data class OutputDevice(
    val id: String,
    val name: String,
    val kind: String,
    val default: Boolean,
)

private val BLUETOOTH = Regex("""bluetooth|\bbt\b""", RegexOption.IGNORE_CASE)
private val USB = Regex("""\busb\b""", RegexOption.IGNORE_CASE)
private val WIRED = Regex("""headphone|headset|tai nghe""", RegexOption.IGNORE_CASE)
private val SPEAKER = Regex("""speaker|\bloa\b""", RegexOption.IGNORE_CASE)

/**
 * api.md `kind` of an output by the keywords in its name, first match wins: bluetooth (`bluetooth`, `bt`), `usb`,
 * headphone or headset (`wired`), speaker or `loa` (`speaker`), else `other`.
 */
fun outputKindOf(name: String): String =
    when {
        BLUETOOTH.containsMatchIn(name) -> "bluetooth"
        USB.containsMatchIn(name) -> "usb"
        WIRED.containsMatchIn(name) -> "wired"
        SPEAKER.containsMatchIn(name) -> "speaker"
        else -> "other"
    }

/** The equalizer's view of an api.md output kind. */
fun equalizerKindOf(kind: String): EqualizerOutputKind =
    when (kind) {
        "bluetooth" -> EqualizerOutputKind.BLUETOOTH
        "usb" -> EqualizerOutputKind.USB
        "wired" -> EqualizerOutputKind.WIRED
        "speaker" -> EqualizerOutputKind.SPEAKER
        else -> EqualizerOutputKind.OTHER
    }

const val DEFAULT_OUTPUT_NAME = "Primary Sound Driver"

/** The default output among [names]: "Primary Sound Driver" when present, else the first. */
fun defaultOutputName(names: List<String>): String? = names.firstOrNull { it == DEFAULT_OUTPUT_NAME } ?: names.firstOrNull()

/** The id `player.output.list` reports as current: the persisted [chosen] one while it still exists, else the default. */
fun currentOutputId(
    names: List<String>,
    chosen: String?,
): String? = chosen?.takeIf { it in names } ?: defaultOutputName(names)

/** Builds the device list from mixer [names] (already filtered to those that can play 16-bit stereo). */
fun outputDevices(names: List<String>): List<OutputDevice> {
    val default = defaultOutputName(names)
    return names.distinct().map { OutputDevice(id = it, name = it, kind = outputKindOf(it), default = it == default) }
}

/**
 * Mixers that can open a `SourceDataLine` for 44.1 kHz 16-bit stereo. `AudioSystem.getMixerInfo(): Array<Mixer.Info>`,
 * `AudioSystem.getMixer(info).isLineSupported(DataLine.Info)`; ports and capture-only mixers are dropped.
 */
fun playbackMixerNames(): List<String> {
    val probe = DataLine.Info(SourceDataLine::class.java, AudioFormat(44100f, 16, 2, true, false))
    return AudioSystem
        .getMixerInfo()
        .filter { info -> runCatching { AudioSystem.getMixer(info).isLineSupported(probe) }.getOrDefault(false) }
        .map { it.name }
}
