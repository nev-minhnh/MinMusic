package com.metrolist.desktop.storage

import com.metrolist.shared.playback.ffmpeg.FFmpegLogCallback
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avformat.AVOutputFormat
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_FLAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MJPEG
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MP3
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VORBIS
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open2
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_TIME_BASE
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.io.IOException

/** The container a codec is stored in without re-encoding, plus the format row (FormatEntity) values. */
internal enum class StoredAudioFormat(
    val extension: String,
    val mimeType: String,
    val codecs: String,
    val muxer: String,
) {
    OPUS(".opus", "audio/ogg", "opus", "ogg"),
    VORBIS(".ogg", "audio/ogg", "vorbis", "ogg"),
    AAC(".m4a", "audio/mp4", "mp4a.40.2", "ipod"),
    MP3(".mp3", "audio/mpeg", "mp3", "mp3"),
    FLAC(".flac", "audio/flac", "flac", "flac"),
}

/**
 * Writes the audio of [input] (opened from [sourceFile]) to [output] with [tags] and no re-encoding. MP3 and FLAC files
 * are copied byte for byte (or remuxed first when they came in another container) and then tagged in Kotlin.
 */
internal suspend fun exportTaggedAudio(
    input: AudioRemuxSource,
    sourceFile: File,
    output: File,
    tags: DownloadTags,
    onProgress: (Float) -> Unit = {},
) {
    when (val format = input.format ?: throw IOException("Audio codec cannot be stored without re-encoding")) {
        StoredAudioFormat.MP3, StoredAudioFormat.FLAC -> {
            val untagged =
                if (input.canCopyFile) {
                    sourceFile
                } else {
                    File(output.parentFile, output.name + ".untagged").also { input.remuxTo(it, tags, onProgress) }
                }
            try {
                if (format == StoredAudioFormat.MP3) writeId3Tags(untagged, output, tags) else writeFlacTags(untagged, output, tags)
                onProgress(1f)
            } finally {
                if (untagged != sourceFile) untagged.delete()
            }
        }
        StoredAudioFormat.OPUS, StoredAudioFormat.VORBIS, StoredAudioFormat.AAC -> input.remuxTo(output, tags, onProgress)
    }
}

/**
 * Stream-copies one downloaded audio stream into its natural container: Opus (WebM/HLS) -> .opus (Ogg), AAC (MP4/TS/HLS)
 * -> .m4a, MP3 -> .mp3, FLAC -> .flac. Nothing is re-encoded. [input] is a local file or an HTTP(S)/HLS URL (FFmpeg reads
 * HLS playlists and fMP4/TS segments itself), so SoundCloud HLS downloads need no separate segment fetcher.
 *
 * Tags: MP4 and Ogg get them from the muxer (MP4 cover as an attached picture, Ogg cover as METADATA_BLOCK_PICTURE);
 * MP3 and FLAC are tagged afterwards by [writeId3Tags] / [writeFlacTags], byte-identical to Android's writers.
 */
internal class AudioRemuxSource private constructor(
    private val input: AVFormatContext,
    private val audioIndex: Int,
    private val isLocalFile: Boolean,
) : AutoCloseable {
    private var closed = false

    private val audioStream: AVStream
        get() = input.streams(audioIndex)

    /** Null when the codec has no stream-copy target (the caller falls back to transcoding). */
    val format: StoredAudioFormat? =
        when (audioStream.codecpar().codec_id()) {
            AV_CODEC_ID_OPUS -> StoredAudioFormat.OPUS
            AV_CODEC_ID_VORBIS -> StoredAudioFormat.VORBIS
            AV_CODEC_ID_AAC -> StoredAudioFormat.AAC
            AV_CODEC_ID_MP3 -> StoredAudioFormat.MP3
            AV_CODEC_ID_FLAC -> StoredAudioFormat.FLAC
            else -> null
        }

    private val containerName: String =
        input
            .iformat()
            ?.takeUnless { it.isNull }
            ?.name()
            ?.takeUnless { it.isNull }
            ?.string
            .orEmpty()

    /** Bits per second of the audio stream, or of the whole input when the stream does not declare one. */
    val bitrate: Int? =
        (audioStream.codecpar().bit_rate().takeIf { it > 0 } ?: input.bit_rate().takeIf { it > 0 })
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()

    val sampleRate: Int? = audioStream.codecpar().sample_rate().takeIf { it > 0 }

    val durationMs: Long? = input.duration().takeIf { it > 0 }?.let { it * 1000L / AV_TIME_BASE }

    /** MP3 and FLAC files already in their own container are copied byte for byte; only the tags change. */
    val canCopyFile: Boolean
        get() =
            isLocalFile &&
                (
                    (format == StoredAudioFormat.MP3 && containerName.split(',').any { it == "mp3" }) ||
                        (format == StoredAudioFormat.FLAC && containerName.split(',').any { it == "flac" })
                )

    /**
     * Writes the audio stream to [output] in [format]'s container. MP3/FLAC outputs are untagged (no ID3/Vorbis block);
     * tag them with [writeId3Tags] / [writeFlacTags].
     */
    suspend fun remuxTo(
        output: File,
        tags: DownloadTags,
        onProgress: (Float) -> Unit = {},
    ) {
        val coroutineContext = currentCoroutineContext()
        val target = format ?: throw IOException("Audio codec cannot be stored without re-encoding")
        val out = AVFormatContext(null)
        val packet = av_packet_alloc()?.takeUnless { it.isNull } ?: throw IOException("Could not allocate an audio packet")
        try {
            ffmpeg(
                avformat_alloc_output_context2(out, null as AVOutputFormat?, target.muxer, output.path),
                "create the ${target.muxer} muxer",
            )
            val inStream = audioStream
            val outStream = avformat_new_stream(out, null as AVCodec?) ?: throw IOException("Could not create the audio stream")
            ffmpeg(avcodec_parameters_copy(outStream.codecpar(), inStream.codecpar()), "copy the audio parameters")
            outStream.codecpar().codec_tag(0)
            outStream.time_base(inStream.time_base())

            when (target) {
                StoredAudioFormat.AAC -> out.metadata(dictionary(tags.mp4Metadata()))
                StoredAudioFormat.OPUS, StoredAudioFormat.VORBIS -> {
                    // The Ogg muxer writes the stream's comment header; keep the global copy for other readers.
                    out.metadata(dictionary(tags.vorbisMetadata()))
                    outStream.metadata(dictionary(tags.vorbisMetadata()))
                }
                StoredAudioFormat.MP3, StoredAudioFormat.FLAC -> Unit
            }
            val cover = tags.cover?.takeIf { target == StoredAudioFormat.AAC && it.jpeg.isNotEmpty() }
            val coverStream = cover?.let { addCoverStream(out, it) }

            if ((out.oformat().flags() and AVFMT_NOFILE) == 0) {
                val io = AVIOContext(null)
                ffmpeg(avio_open2(io, output.path, AVIO_FLAG_WRITE, null as AVIOInterruptCB?, null as AVDictionary?), "open ${output.name}")
                out.pb(io)
            }
            val headerOptions = AVDictionary(null)
            try {
                // MP3 tags are written in Kotlin afterwards; an FFmpeg ID3 header would only be stripped again.
                if (target == StoredAudioFormat.MP3) av_dict_set(headerOptions, "id3v2_version", "0", 0)
                ffmpeg(avformat_write_header(out, headerOptions), "write the ${target.muxer} header")
            } finally {
                av_dict_free(headerOptions)
            }
            if (cover != null && coverStream != null) writeCover(out, coverStream, cover)

            val inTimeBase = inStream.time_base()
            val startSeconds = input.start_time().takeIf { it != AV_NOPTS_VALUE && it > 0 }?.let { it.toDouble() / AV_TIME_BASE } ?: 0.0
            val durationSeconds = durationMs?.let { it / 1000.0 }
            while (true) {
                coroutineContext.ensureActive()
                val result = av_read_frame(input, packet)
                if (result == AVERROR_EOF) break
                if (result == AVERROR_EAGAIN()) continue
                ffmpeg(result, "read the downloaded audio")
                try {
                    if (packet.stream_index() != audioIndex) continue
                    if (target == StoredAudioFormat.OPUS && packet.duration() <= 0) fillOpusDuration(packet, inStream)
                    if (durationSeconds != null && durationSeconds > 0 && packet.pts() != AV_NOPTS_VALUE) {
                        val seconds = packet.pts().toDouble() * inTimeBase.num() / inTimeBase.den() - startSeconds
                        onProgress((seconds / durationSeconds).toFloat().coerceIn(0f, 1f))
                    }
                    av_packet_rescale_ts(packet, inTimeBase, outStream.time_base())
                    packet.stream_index(outStream.index())
                    packet.pos(-1)
                    ffmpeg(av_write_frame(out, packet), "write an audio packet")
                } finally {
                    av_packet_unref(packet)
                }
            }
            ffmpeg(av_write_trailer(out), "finish ${output.name}")
            onProgress(1f)
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            av_packet_free(packet)
            if (!out.isNull) {
                out.pb()?.takeUnless { it.isNull }?.let { avio_closep(it) }
                avformat_free_context(out)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        avformat_close_input(input)
    }

    private fun addCoverStream(
        out: AVFormatContext,
        cover: DownloadCover,
    ): AVStream {
        val stream = avformat_new_stream(out, null as AVCodec?) ?: throw IOException("Could not create the cover stream")
        val parameters = stream.codecpar()
        parameters.codec_type(AVMEDIA_TYPE_VIDEO)
        parameters.codec_id(AV_CODEC_ID_MJPEG)
        parameters.width(cover.width)
        parameters.height(cover.height)
        // The MP4 muxer stores a stream whose disposition is exactly ATTACHED_PIC as the iTunes "covr" atom.
        stream.disposition(AV_DISPOSITION_ATTACHED_PIC)
        av_make_q(1, 90_000).use { stream.time_base(it) }
        return stream
    }

    private fun writeCover(
        out: AVFormatContext,
        stream: AVStream,
        cover: DownloadCover,
    ) {
        val packet = av_packet_alloc()?.takeUnless { it.isNull } ?: throw IOException("Could not allocate the cover packet")
        try {
            ffmpeg(av_new_packet(packet, cover.jpeg.size), "allocate the cover packet")
            packet.data().put(cover.jpeg, 0, cover.jpeg.size)
            packet.stream_index(stream.index())
            packet.flags(packet.flags() or AV_PKT_FLAG_KEY)
            packet.pts(0)
            packet.dts(0)
            ffmpeg(av_write_frame(out, packet), "write the cover")
        } finally {
            av_packet_free(packet)
        }
    }

    // WebM blocks may carry no duration; the Ogg muxer derives granule positions from it, so read it from the TOC byte.
    private fun fillOpusDuration(
        packet: AVPacket,
        stream: AVStream,
    ) {
        if (packet.size() <= 0) return
        val data = packet.data() ?: return
        val toc = data.get(0L).toInt() and 0xff
        val config = toc shr 3
        val frameSamples =
            when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
                config < 16 -> intArrayOf(480, 960)[config % 2]
                else -> intArrayOf(120, 240, 480, 960)[config % 4]
            }
        val frames =
            when (toc and 0x3) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size() > 1) data.get(1L).toInt() and 0x3f else return
            }
        val samples = frameSamples.toLong() * frames
        val timeBase = stream.time_base()
        if (timeBase.num() <= 0 || timeBase.den() <= 0) return
        packet.duration(Math.round(samples.toDouble() * timeBase.den() / (OPUS_SAMPLE_RATE.toDouble() * timeBase.num())))
    }

    companion object {
        private const val OPUS_SAMPLE_RATE = 48_000

        /** Opens [path] (a local file or HTTP(S)/HLS URL with [headers]) and picks its first audio stream. */
        fun open(
            path: String,
            headers: Map<String, String> = emptyMap(),
        ): AudioRemuxSource {
            FFmpegLogCallback.set()
            FFmpegLogCallback.setLevel(AV_LOG_WARNING)
            val remote = path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)
            val input = AVFormatContext(null)
            val options = AVDictionary(null)
            try {
                if (remote) {
                    av_dict_set(options, "reconnect", "1", 0)
                    av_dict_set(options, "reconnect_streamed", "1", 0)
                    av_dict_set(options, "reconnect_delay_max", "5", 0)
                    av_dict_set(options, "rw_timeout", "30000000", 0)
                    if (path.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                        // Source HLS segments carry no common media extension; see AudioDecoder.
                        av_dict_set(options, "extension_picky", "0", 0)
                        av_dict_set(options, "allowed_extensions", "ALL", 0)
                    }
                    headers.entries
                        .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                        ?.value
                        ?.takeIf(String::isNotBlank)
                        ?.let { av_dict_set(options, "user_agent", it, 0) }
                    headers
                        .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                        .takeIf { it.isNotEmpty() }
                        ?.entries
                        ?.joinToString(separator = "\r\n", postfix = "\r\n") { "${it.key}: ${it.value}" }
                        ?.let { av_dict_set(options, "headers", it, 0) }
                }
                val result = avformat_open_input(input, path, null as AVInputFormat?, options)
                // Never put the URL in the message: it may carry signed tokens.
                if (result < 0) throw IOException("Could not open the downloaded audio (FFmpeg error $result)")
            } finally {
                av_dict_free(options)
            }
            try {
                ffmpeg(avformat_find_stream_info(input, null as PointerPointer<*>?), "read the audio stream info")
                val audioIndex =
                    (0 until input.nb_streams()).firstOrNull { input.streams(it).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO }
                        ?: throw IOException("The download has no audio stream")
                return AudioRemuxSource(input, audioIndex, isLocalFile = !remote)
            } catch (error: Throwable) {
                avformat_close_input(input)
                throw error
            }
        }

        private fun dictionary(values: Map<String, String>): AVDictionary {
            val dictionary = AVDictionary(null)
            values.forEach { (key, value) ->
                BytePointer(key, "UTF-8").use { nativeKey ->
                    BytePointer(value, "UTF-8").use { nativeValue ->
                        ffmpeg(av_dict_set(dictionary, nativeKey, nativeValue, 0), "set $key metadata")
                    }
                }
            }
            return dictionary
        }

        private fun ffmpeg(
            result: Int,
            action: String,
        ) {
            if (result < 0) throw IOException("Could not $action (FFmpeg error $result)")
        }
    }
}
