package com.metrolist.shared.source

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.youtube.playback.LocalAudioSource
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends YouTube and local-file ids to [delegate] unchanged and every other source's ids to its
 * [MusicSource]. A downloaded copy of a foreign song plays even while its source is disabled.
 */
class SourceRoutingStreamResolver(
    private val delegate: StreamResolver,
    private val registry: SourceRegistry,
    private val localAudioSource: LocalAudioSource? = null,
) : StreamResolver by delegate {
    override suspend fun resolve(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource =
        if (isForeignSourceId(videoId)) {
            resolveForeign(videoId, audioQuality, sourceSong, preferLocal = true)
        } else {
            delegate.resolve(videoId, hints, audioQuality, sourceSong)
        }

    override suspend fun refresh(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource =
        if (isForeignSourceId(videoId)) {
            resolveForeign(videoId, audioQuality, sourceSong, preferLocal = true)
        } else {
            delegate.refresh(videoId, hints, audioQuality, sourceSong)
        }

    override suspend fun resolveRemote(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        excludedProfiles: Set<String>,
    ): PlaybackResource =
        if (isForeignSourceId(videoId)) {
            resolveForeign(videoId, audioQuality, sourceSong, preferLocal = false)
        } else {
            delegate.resolveRemote(videoId, hints, audioQuality, sourceSong, refresh, excludedProfiles)
        }

    override suspend fun clearPlaybackFailures(videoId: String) {
        if (!isForeignSourceId(videoId)) delegate.clearPlaybackFailures(videoId)
    }

    override suspend fun reportPlaybackFailure(
        videoId: String,
        clientName: String?,
        responseCode: Int?,
        profileId: String?,
        hints: ContentHints,
        category: PlaybackFailureCategory,
    ) {
        if (!isForeignSourceId(videoId)) delegate.reportPlaybackFailure(videoId, clientName, responseCode, profileId, hints, category)
    }

    private suspend fun resolveForeign(
        id: String,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        preferLocal: Boolean,
    ): PlaybackResource {
        if (preferLocal) localAudioSource?.resolve(id, sourceSong)?.let { return it }
        val source = sourceOf(id)
        return try {
            registry.call(source) { it.stream(id, audioQuality.toSourceQuality()) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            throw error
        } catch (error: SourceUnavailableException) {
            throw StreamResolveException(
                reason = StreamResolveException.Reason.UNAVAILABLE,
                message = error.message ?: "Source ${source.key} is unavailable.",
                cause = error,
            )
        } catch (error: Exception) {
            throw StreamResolveException(
                reason = StreamResolveException.Reason.NETWORK,
                message = "Unable to fetch stream data from ${source.key}.",
                cause = error,
            )
        }
    }
}
