package com.metrolist.shared.playback.ffmpeg

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/** Output device chosen through `player.output.set` (desktop core); null keeps the default routing. */
internal object AudioOutputPreference {
    @Volatile
    var mixerName: String? = null
}

/** Opens a line on the preferred mixer, or returns null (no preference, unknown mixer, line failure) so the caller falls back. */
internal fun openPreferredAudioLine(
    preferred: String?,
    format: AudioFormat,
    bufferSize: Int,
): SourceDataLine? {
    if (preferred.isNullOrBlank()) return null
    val mixerInfo = AudioSystem.getMixerInfo().firstOrNull { it.name == preferred } ?: return null
    var line: SourceDataLine? = null
    var opened = false
    return try {
        line = AudioSystem.getMixer(mixerInfo).getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        line.open(format, bufferSize)
        opened = true
        line
    } catch (_: Exception) {
        null
    } finally {
        if (!opened) runCatching { line?.close() }
    }
}

internal fun openAudioLine(
    format: AudioFormat,
    bufferSize: Int,
): SourceDataLine =
    openPreferredAudioLine(AudioOutputPreference.mixerName, format, bufferSize) ?: openAudioLine(
        format,
        bufferSize,
        System.getProperty("os.name"),
        System.getProperty(SourceDataLine::class.java.name),
        { AudioSystem.getMixerInfo().toList() },
        { mixer, info ->
            (if (mixer == null) AudioSystem.getLine(info) else AudioSystem.getMixer(mixer).getLine(info)) as SourceDataLine
        },
        Thread::sleep,
    )

internal fun openAudioLine(
    format: AudioFormat,
    bufferSize: Int,
    osName: String,
    configuredLine: String?,
    mixers: () -> List<Mixer.Info>,
    getLine: (Mixer.Info?, DataLine.Info) -> SourceDataLine,
    sleep: (Long) -> Unit,
): SourceDataLine {
    val info = DataLine.Info(SourceDataLine::class.java, format)
    var failure: Exception? = null
    for (attempt in 0..3) {
        if (attempt > 0) sleep(100L shl (attempt - 1))
        for (mixer in audioOutputMixers(osName, configuredLine, mixers())) {
            var line: SourceDataLine? = null
            var opened = false
            try {
                line = getLine(mixer, info)
                line.open(format, bufferSize)
                opened = true
                return line
            } catch (error: Exception) {
                if (error !is LineUnavailableException && error !is IllegalArgumentException) throw error
                failure = error
            } finally {
                if (!opened) {
                    runCatching { line?.close() }
                }
            }
        }
    }
    throw LineUnavailableException().apply { failure?.let(::initCause) }
}

internal fun audioOutputMixers(
    osName: String,
    configuredLine: String?,
    mixers: List<Mixer.Info>,
): List<Mixer.Info?> {
    if (!configuredLine.isNullOrBlank()) return listOf(null)
    if (!osName.equals("Linux", ignoreCase = true)) return listOf(null) + mixers
    // Do not bypass an available system route with Java Sound's default, which can select hardware or loopback devices.
    val systemMixers =
        mixers.filter { it.name.endsWith(" [default]") } +
            mixers.filter { it.name.endsWith(" [pipewire]") || it.name.endsWith(" [pulse]") }
    return systemMixers.ifEmpty { listOf(null) }
}

fun Throwable.isAudioOutputUnavailable(): Boolean =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.any { it is LineUnavailableException }
