package com.metrolist.shared.youtube.playback

import com.metrolist.shared.db.entities.FormatEntity

/**
 * The audio codec string of a resolved stream. YouTube streams may carry it only inside the MIME type
 * (`audio/webm; codecs="opus"`) or only as an itag (SABR), so fall back to those before giving up.
 */
fun PlaybackResource.audioCodecs(): String? =
    codecs?.takeIf(String::isNotBlank)
        ?: mimeType?.let(::codecsFromMimeType)
        ?: itag?.let(::youTubeItagCodec)

private fun codecsFromMimeType(mimeType: String): String? =
    mimeType
        .substringAfter("codecs=", missingDelimiterValue = "")
        .trim()
        .trim('"', '\'')
        .substringBefore(',')
        .trim()
        .takeIf(String::isNotBlank)

/** Codec of the known YouTube audio itags (WebM Opus 249/250/251/774, MP4 AAC 139/140/141). */
private fun youTubeItagCodec(itag: Int): String? =
    when (itag) {
        249, 250, 251, 774 -> "opus"
        139, 140, 141 -> "mp4a.40.2"
        else -> null
    }

fun PlaybackResource.toCachedFormatEntity(
    id: String,
    mimeType: String?,
    contentLengthBytes: Long,
): FormatEntity? {
    val itag = itag ?: return null
    val storedMimeType = mimeType?.takeIf(String::isNotBlank) ?: return null
    val codecs = audioCodecs() ?: return null
    val bitrate = bitrate ?: return null
    return FormatEntity(
        id = id,
        itag = itag,
        mimeType = storedMimeType,
        codecs = codecs,
        bitrate = bitrate,
        sampleRate = sampleRate,
        contentLength = contentLengthBytes,
        loudnessDb = loudnessDb,
        perceptualLoudnessDb = perceptualLoudnessDb,
        playbackUrl = null,
    )
}

fun PlaybackResource.withCachedFormat(format: FormatEntity?): PlaybackResource =
    format?.let {
        copy(
            itag = it.itag,
            mimeType = it.mimeType,
            codecs = it.codecs,
            bitrate = it.bitrate,
            sampleRate = it.sampleRate,
            loudnessDb = it.loudnessDb,
            perceptualLoudnessDb = it.perceptualLoudnessDb,
            contentLengthBytes = it.contentLength,
        )
    } ?: this
