package com.metrolist.android.source.soundcloud

/** Prefix of every SoundCloud track id handed to the rest of the app, e.g. `sc:123456`. */
const val SOUNDCLOUD_ID_PREFIX = "sc:"

enum class SoundCloudSearchFilter(
    internal val pathSegment: String,
) {
    TRACKS("tracks"),
    PLAYLISTS("playlists"),
    USERS("users"),
}

/** SoundCloud's per-track access policy as returned by api-v2. */
enum class SoundCloudPolicy {
    ALLOW,
    MONETIZE,

    /** 30-second preview of a Go+ track. Never played or downloaded in full. */
    SNIP,

    /** Not available in the requester's region. */
    BLOCK,
    UNKNOWN,
    ;

    val isPlayable: Boolean get() = this == ALLOW || this == MONETIZE

    internal companion object {
        fun from(value: String?): SoundCloudPolicy = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

data class SoundCloudTrack(
    /** `sc:<numeric id>`. */
    val id: String,
    val numericId: Long,
    val title: String,
    val artist: String,
    val artistUrl: String?,
    val durationMs: Long,
    /** Largest square artwork, falling back to the uploader avatar like the website does. */
    val artworkUrl: String?,
    val permalinkUrl: String,
    /** Album title from the publisher metadata, when the uploader provided one. */
    val album: String?,
    val policy: SoundCloudPolicy,
    val streamable: Boolean,
) {
    /**
     * Whether [SoundCloudClient.stream] is expected to resolve. Downloads follow the same rule
     * (owner decision 2026-11-21): any track whose stream resolves may be downloaded, regardless of
     * SoundCloud's `downloadable` flag, but previews and DRM streams are never used.
     */
    val isPlayable: Boolean get() = streamable && policy.isPlayable
}

data class SoundCloudPlaylistSummary(
    val numericId: Long,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val permalinkUrl: String,
    val trackCount: Int,
    val isAlbum: Boolean,
)

data class SoundCloudPlaylist(
    val summary: SoundCloudPlaylistSummary,
    val tracks: List<SoundCloudTrack>,
)

data class SoundCloudUser(
    val numericId: Long,
    val name: String,
    val avatarUrl: String?,
    val permalinkUrl: String,
    val followers: Long,
)

sealed interface SoundCloudSearchItem {
    data class Track(
        val track: SoundCloudTrack,
    ) : SoundCloudSearchItem

    data class Playlist(
        val playlist: SoundCloudPlaylistSummary,
    ) : SoundCloudSearchItem

    data class User(
        val user: SoundCloudUser,
    ) : SoundCloudSearchItem
}

/** One page of results. Pass [nextPageToken] back to fetch the following page; `null` means the end. */
data class SoundCloudPage<T>(
    val items: List<T>,
    val nextPageToken: String?,
)

data class SoundCloudStream(
    val url: String,
    /** `application/x-mpegURL` for HLS, otherwise the progressive container type. */
    val mimeType: String,
    val bitrateKbps: Int,
    /** `aac`, `opus` or `mp3`. */
    val codec: String,
    val isHls: Boolean,
    /** Resolve again after this time; SoundCloud stream URLs can expire after about five minutes. */
    val expiresAtMs: Long,
    /** SoundCloud transcoding preset, e.g. `aac_160k`. */
    val preset: String,
)

/** Typed failures callers can show or recover from without depending on NewPipeExtractor types. */
sealed class SoundCloudException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Go+ track: SoundCloud only serves a 30-second preview (policy `SNIP`). */
    class PreviewOnly(
        cause: Throwable? = null,
    ) : SoundCloudException("SoundCloud only offers a preview of this track", cause)

    /** Blocked in the requester's region (policy `BLOCK`). */
    class Blocked(
        cause: Throwable? = null,
    ) : SoundCloudException("This SoundCloud track is blocked in this region", cause)

    /** Private, removed, or otherwise unavailable content. */
    class Unavailable(
        message: String,
        cause: Throwable? = null,
    ) : SoundCloudException(message, cause)

    /** The track has no unencrypted transcoding the client can play. */
    class NoPlayableStream : SoundCloudException("No playable SoundCloud stream")

    /** SoundCloud returned something the client could not parse or resolve. */
    class ExtractionFailed(
        message: String,
        cause: Throwable? = null,
    ) : SoundCloudException(message, cause)
}
