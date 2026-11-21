package com.metrolist.shared.source

/**
 * Where a song id comes from. YouTube keeps bare ids so existing libraries, backups and
 * Listen Together peers stay compatible; every other source namespaces its ids with [prefix].
 */
enum class SourceId(
    val prefix: String,
    val key: String,
) {
    YT("", "yt"),
    SC("sc:", "sc"),
    ZING("zing:", "zing"),
    NCT("nct:", "nct"),
    LOCAL("local_", "local"),
}

fun sourceOf(id: String): SourceId = SourceId.entries.firstOrNull { it.prefix.isNotEmpty() && id.startsWith(it.prefix) } ?: SourceId.YT

fun isYouTubeId(id: String): Boolean = sourceOf(id) == SourceId.YT

/** Songs whose playback goes through a [MusicSource] rather than YouTube or a local file. */
fun isForeignSourceId(id: String): Boolean = sourceOf(id).let { it != SourceId.YT && it != SourceId.LOCAL }
