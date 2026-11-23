package com.metrolist.core.api.importfiles

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_TIME_BASE
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.javacpp.PointerPointer

/** Tags of a local audio file, read with FFmpeg (container metadata, then the audio stream's). */
internal object LocalProbe {
    data class Tags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val year: Int?,
        val durationSec: Int,
    )

    /** Null when FFmpeg cannot open the file or it has no audio stream. */
    fun probe(path: String): Tags? {
        val ctx = AVFormatContext(null)
        if (avformat_open_input(ctx, path, null as AVInputFormat?, null as AVDictionary?) < 0) return null
        try {
            if (avformat_find_stream_info(ctx, null as PointerPointer<*>?) < 0) return null
            val audio = (0 until ctx.nb_streams()).firstOrNull { ctx.streams(it).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO } ?: return null
            val streamMeta = ctx.streams(audio).metadata()

            fun tag(key: String): String? =
                listOf(ctx.metadata(), streamMeta).firstNotNullOfOrNull { dict ->
                    if (dict == null || dict.isNull) return@firstNotNullOfOrNull null
                    av_dict_get(dict, key, null as AVDictionaryEntry?, 0)?.takeUnless { it.isNull }?.value()?.string?.trim()?.takeIf(String::isNotEmpty)
                }
            val duration = ctx.duration().takeIf { it > 0 }?.let { (it / AV_TIME_BASE).toInt() } ?: -1
            return Tags(
                title = tag("title"),
                artist = tag("artist") ?: tag("album_artist"),
                album = tag("album"),
                year = tag("date")?.let { YEAR.find(it)?.value?.toIntOrNull() },
                durationSec = duration,
            )
        } finally {
            avformat_close_input(ctx)
        }
    }

    private val YEAR = Regex("\\d{4}")
}
