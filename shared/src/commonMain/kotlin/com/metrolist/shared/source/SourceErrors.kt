package com.metrolist.shared.source

import com.metrolist.innertubex.extraction.StreamResolveException

/** Why a source refuses one item. It is the cause of the [StreamResolveException] a [MusicSource] throws. */
class SourceContentException(
    val source: SourceId,
    val reason: Reason,
    cause: Throwable? = null,
) : Exception("Source ${source.key} cannot play this item: ${reason.name.lowercase()}", cause) {
    enum class Reason {
        /** Only a preview exists for the user's account, e.g. SoundCloud Go+ tracks. */
        PREVIEW_ONLY,
        GEO_BLOCKED,

        /** Every stream needs a VIP subscription the user's account does not have. */
        VIP_REQUIRED,
        NOT_FOUND,
    }
}

/** Content the source will not serve to this user; does not count against the source's health. */
fun sourceUnavailable(
    source: SourceId,
    reason: SourceContentException.Reason,
    cause: Throwable? = null,
): StreamResolveException {
    val content = SourceContentException(source, reason, cause)
    return StreamResolveException(
        reason = StreamResolveException.Reason.UNAVAILABLE,
        message = content.message ?: reason.name,
        cause = content,
    )
}

fun sourceNetworkFailure(
    source: SourceId,
    cause: Exception,
): StreamResolveException =
    StreamResolveException(
        reason = StreamResolveException.Reason.NETWORK,
        message = "Unable to fetch stream data from ${source.key}.",
        cause = cause,
    )
