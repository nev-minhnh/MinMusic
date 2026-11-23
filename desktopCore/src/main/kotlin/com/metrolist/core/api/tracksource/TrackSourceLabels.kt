package com.metrolist.core.api.tracksource

import com.metrolist.core.api.Quality
import com.metrolist.core.api.jsonOf
import com.metrolist.core.api.sources.SourcesMapper
import com.metrolist.shared.source.QualityRanker
import com.metrolist.shared.source.SourceContentException
import com.metrolist.shared.source.SourceId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure mapping for `track.sources`: stream tier to the api Quality label, refusal reason to the option hint. */
object TrackSourceLabels {
    /** The label of a stream of [tier] at [kbps]. Null for an unknown tier (the caller falls back to the source's max quality). */
    fun quality(
        tier: QualityRanker.Tier,
        kbps: Int?,
    ): Quality? =
        when (tier) {
            QualityRanker.Tier.LOSSLESS -> Quality("Lossless", lossless = true)
            QualityRanker.Tier.HIGH -> if ((kbps ?: 0) >= 300) Quality("320", lossless = false, kbps = 320) else Quality("256", lossless = false, kbps = 256)
            QualityRanker.Tier.OPUS_STANDARD -> Quality("Opus 160", lossless = false, kbps = 160)
            QualityRanker.Tier.AAC_STANDARD -> Quality("AAC 160", lossless = false, kbps = 160)
            QualityRanker.Tier.LOW -> Quality("128", lossless = false, kbps = 128)
            QualityRanker.Tier.UNKNOWN -> null
        }

    /** `vip_login`, `region` or `preview_only`; null when the refusal means the item does not exist on that source. */
    fun hint(reason: SourceContentException.Reason): String? =
        when (reason) {
            SourceContentException.Reason.VIP_REQUIRED -> "vip_login"
            SourceContentException.Reason.GEO_BLOCKED -> "region"
            SourceContentException.Reason.PREVIEW_ONLY -> "preview_only"
            SourceContentException.Reason.NOT_FOUND -> null
        }

    /** The refusal reason inside a stream failure (the cause chain of a StreamResolveException), if any. */
    fun reasonOf(error: Throwable): SourceContentException.Reason? =
        generateSequence(error) { it.cause }.filterIsInstance<SourceContentException>().firstOrNull()?.reason

    /** One `SourceOption`: `{source, id, quality, auto, available, hint?}`. */
    fun option(
        source: SourceId,
        id: String,
        quality: Quality,
        auto: Boolean,
        available: Boolean,
        hint: String? = null,
    ): JsonObject =
        jsonOf(
            "source" to JsonPrimitive(source.key),
            "id" to JsonPrimitive(id),
            "quality" to SourcesMapper.qualityJson(quality),
            "auto" to JsonPrimitive(auto),
            "available" to JsonPrimitive(available),
            "hint" to hint?.let { JsonPrimitive(it) },
        )
}
