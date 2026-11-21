package com.metrolist.android.playback

import java.net.URI

internal sealed class HlsException(
    message: String,
) : Exception(message) {
    class Encrypted : HlsException("Encrypted HLS playlists are not supported")

    class Unsupported(
        reason: String,
    ) : HlsException(reason)

    class Http(
        val code: Int,
        url: String,
    ) : HlsException("HTTP $code for $url")

    class SizeLimit(
        val limit: Long,
    ) : HlsException("HLS download exceeds $limit bytes")

    class Export : HlsException("Could not convert HLS audio to M4A")
}

internal data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val codecs: String?,
    val audioRendition: Boolean = false,
)

internal sealed interface HlsPlaylist {
    data class Master(
        val variants: List<HlsVariant>,
    ) : HlsPlaylist

    data class Media(
        val initSegmentUrl: String?,
        val segmentUrls: List<String>,
    ) : HlsPlaylist {
        val isFragmentedMp4: Boolean get() = initSegmentUrl != null
    }
}

private val VIDEO_CODEC_PREFIXES = listOf("avc", "hvc", "hev", "vp0", "av01", "dvh")

internal fun parseHlsPlaylist(
    text: String,
    baseUrl: String,
): HlsPlaylist {
    val lines = text.lineSequence().map { it.trim().trimStart('﻿') }.filter { it.isNotEmpty() }.toList()
    require(lines.firstOrNull() == "#EXTM3U") { "Not an HLS playlist" }

    fun resolve(reference: String): String = URI(baseUrl).resolve(reference).toString()
    if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
        val variants = mutableListOf<HlsVariant>()
        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = parseHlsAttributes(line.substringAfter(':'))
                val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                variants += HlsVariant(resolve(uri), attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L, attrs["CODECS"])
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                val attrs = parseHlsAttributes(line.substringAfter(':'))
                val uri = attrs["URI"]
                if (attrs["TYPE"] == "AUDIO" && uri != null) {
                    variants += HlsVariant(resolve(uri), 0L, null, audioRendition = true)
                }
            }
        }
        return HlsPlaylist.Master(variants)
    }
    var mapUrl: String? = null
    val segments = mutableListOf<String>()
    for (line in lines) {
        when {
            line.startsWith("#EXT-X-KEY:") -> {
                val method = parseHlsAttributes(line.substringAfter(':'))["METHOD"]
                if (method != null && method != "NONE") throw HlsException.Encrypted()
            }
            line.startsWith("#EXT-X-MAP:") -> {
                val attrs = parseHlsAttributes(line.substringAfter(':'))
                if (attrs.containsKey("BYTERANGE")) throw HlsException.Unsupported("Byte-range init segments are not supported")
                mapUrl = attrs["URI"]?.let(::resolve)
            }
            line.startsWith("#EXT-X-BYTERANGE") -> throw HlsException.Unsupported("Byte-range segments are not supported")
            !line.startsWith("#") -> segments += resolve(line)
        }
    }
    return HlsPlaylist.Media(mapUrl, segments)
}

/** Picks the highest-bandwidth audio-only variant; falls back to the highest-bandwidth variant overall. */
internal fun selectHlsAudioVariant(variants: List<HlsVariant>): HlsVariant? {
    fun isAudioOnly(variant: HlsVariant): Boolean {
        val codecs = variant.codecs?.lowercase() ?: return variant.audioRendition
        return VIDEO_CODEC_PREFIXES.none { codecs.contains(it) }
    }
    return variants.filter(::isAudioOnly).maxByOrNull { it.bandwidth }
        ?: variants.maxByOrNull { it.bandwidth }
}

internal fun parseHlsAttributes(text: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var index = 0
    while (index < text.length) {
        val eq = text.indexOf('=', index)
        if (eq < 0) break
        val key = text.substring(index, eq).trim()
        val value: String
        var end: Int
        if (eq + 1 < text.length && text[eq + 1] == '"') {
            val close = text.indexOf('"', eq + 2).let { if (it < 0) text.length else it }
            value = text.substring(eq + 2, close)
            end = text.indexOf(',', close).let { if (it < 0) text.length else it }
        } else {
            end = text.indexOf(',', eq).let { if (it < 0) text.length else it }
            value = text.substring(eq + 1, end)
        }
        result[key] = value.trim()
        index = end + 1
    }
    return result
}
