package com.metrolist.core.api.lyrics

import com.metrolist.shared.model.LyricsProvider

/** A `lyrics.search` candidate id: `<provider name>:<index in that provider's results>`. */
data class CandidateRef(
    val provider: String,
    val index: Int,
)

object CandidateId {
    fun format(
        provider: String,
        index: Int,
    ): String = "$provider:$index"

    /** Parses an id made by [format]; null when it is malformed (no colon, empty provider, negative or non-numeric index). */
    fun parse(id: String): CandidateRef? {
        val colon = id.lastIndexOf(':')
        if (colon <= 0) return null
        val index = id.substring(colon + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return CandidateRef(id.substring(0, colon), index)
    }

    /** Provider names accepted by `lyrics.providers.set`: the user-configurable ones. */
    val configurable: List<String> = LyricsProvider.userConfigurable.map { it.name }

    /**
     * Validates a provider order from the client: every name must be configurable and appear once. Returns the
     * normalized list or null when invalid.
     */
    fun validOrder(order: List<String>): List<String>? {
        val trimmed = order.map { it.trim() }
        if (trimmed.any { it !in configurable } || trimmed.toSet().size != trimmed.size) return null
        return trimmed
    }
}
