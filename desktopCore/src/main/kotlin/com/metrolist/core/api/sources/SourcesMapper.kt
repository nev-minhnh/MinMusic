package com.metrolist.core.api.sources

import com.metrolist.core.api.Quality
import com.metrolist.core.api.jsonOf
import com.metrolist.desktop.source.DesktopSourceLoginException
import com.metrolist.shared.source.SourceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One source as the mapper sees it: plain values, no Settings or network. */
data class SourceRow(
    val id: SourceId,
    val enabled: Boolean,
    val degraded: Boolean,
    val signedIn: Boolean?,
    val vip: Boolean?,
    val account: String?,
)

/** Pure mapping of source state to api.md 3.11 `sources.list`. */
object SourcesMapper {
    /** The sources the UI knows: everything except LOCAL, in the order of the api.md SourceId union. */
    val IDS: List<SourceId> = SourceId.entries.filter { it != SourceId.LOCAL }

    const val ACCOUNT_TTL_MS = 10 * 60 * 1000L

    fun name(id: SourceId): String =
        when (id) {
            SourceId.YT -> "YouTube Music"
            SourceId.SC -> "SoundCloud"
            SourceId.ZING -> "Zing MP3"
            SourceId.NCT -> "NhacCuaTui"
            SourceId.LOCAL -> "Local"
        }

    /** `off` when disabled, `degraded` when the kill switch tripped, otherwise `ok`. */
    fun status(
        enabled: Boolean,
        degraded: Boolean,
    ): String =
        when {
            !enabled -> "off"
            degraded -> "degraded"
            else -> "ok"
        }

    /** The best quality the source offers. Zing and NCT give 320 only to a VIP account; SoundCloud never Go+ (AAC 160). */
    fun maxQuality(
        id: SourceId,
        vip: Boolean,
    ): Quality =
        when (id) {
            SourceId.YT -> Quality("Opus 160", lossless = false, kbps = 160)
            SourceId.SC -> Quality("AAC 160", lossless = false, kbps = 160)
            SourceId.ZING, SourceId.NCT -> if (vip) Quality("320", lossless = false, kbps = 320) else Quality("128", lossless = false, kbps = 128)
            SourceId.LOCAL -> Quality("Lossless", lossless = true)
        }

    /** YouTube signs in through `account.*`; only Zing and NCT have their own login. */
    fun canLogin(id: SourceId): Boolean = id == SourceId.ZING || id == SourceId.NCT

    fun qualityJson(quality: Quality): JsonObject =
        jsonOf(
            "label" to JsonPrimitive(quality.label),
            "lossless" to JsonPrimitive(quality.lossless),
            "kbps" to quality.kbps?.let { JsonPrimitive(it) },
        )

    fun item(row: SourceRow): JsonObject =
        jsonOf(
            "id" to JsonPrimitive(row.id.key),
            "name" to JsonPrimitive(name(row.id)),
            "enabled" to JsonPrimitive(row.enabled),
            "status" to JsonPrimitive(status(row.enabled, row.degraded)),
            "signedIn" to row.signedIn?.let { JsonPrimitive(it) },
            "vip" to row.vip?.let { JsonPrimitive(it) },
            "account" to row.account?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) },
            "maxQuality" to qualityJson(maxQuality(row.id, row.vip == true)),
            "canLogin" to JsonPrimitive(canLogin(row.id)),
        )

    fun list(
        rows: List<SourceRow>,
        priority: List<String>,
        autoBest: Boolean,
        autoFallback: Boolean,
    ): JsonObject =
        jsonOf(
            "items" to JsonArray(rows.map(::item)),
            "priority" to JsonArray(priority.map { JsonPrimitive(it) }),
            "autoBest" to JsonPrimitive(autoBest),
            "autoFallback" to JsonPrimitive(autoFallback),
        )

    /** The priority must name every source exactly once. */
    fun isValidPriority(order: List<String>): Boolean = order.size == IDS.size && order.toSet() == IDS.map { it.key }.toSet()

    /** A known, non-LOCAL source id, or null. */
    fun parseId(text: String?): SourceId? = IDS.firstOrNull { it.key == text }

    /** Whether a cached account entry fetched (or attempted) at [fetchedAt] must be refreshed. */
    fun needsRefresh(
        fetchedAt: Long?,
        now: Long,
    ): Boolean = fetchedAt == null || now - fetchedAt >= ACCOUNT_TTL_MS

    /** The api error `(code, retryable)` for a failed Zing/NCT login. Never carries the failure message (it could hold input). */
    fun loginError(reason: DesktopSourceLoginException.Reason): Pair<String, Boolean> =
        when (reason) {
            DesktopSourceLoginException.Reason.AUTH_FAILED -> "unauthorized" to false
            DesktopSourceLoginException.Reason.NETWORK -> "network" to true
            DesktopSourceLoginException.Reason.NOT_SIGNED_IN -> "not_signed_in" to false
            DesktopSourceLoginException.Reason.CANCELLED -> "cancelled" to false
            DesktopSourceLoginException.Reason.UNSUPPORTED, DesktopSourceLoginException.Reason.HELPER_MISSING -> "unsupported" to false
            DesktopSourceLoginException.Reason.UNKNOWN -> "internal" to false
        }
}
