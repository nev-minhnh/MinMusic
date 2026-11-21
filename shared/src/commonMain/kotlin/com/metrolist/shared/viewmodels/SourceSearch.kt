package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.source.SourceCapability
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry

/** Songs one non-YouTube source returned for the submitted query. */
data class SourceSearchSection(
    val items: List<SongItem> = emptyList(),
    val isLoading: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Search across the enabled non-YouTube sources, beside the YouTube results. [selectedSource] null means all sources;
 * [SourceId.YT] shows only the YouTube results.
 */
data class SourceSearchState(
    val query: String = "",
    val sources: List<SourceId> = emptyList(),
    val selectedSource: SourceId? = null,
    val sections: Map<SourceId, SourceSearchSection> = emptyMap(),
) {
    val showsYouTube: Boolean
        get() = selectedSource == null || selectedSource == SourceId.YT

    val hasItems: Boolean
        get() = sections.values.any { it.items.isNotEmpty() }
}

/** Search chips list sources in this order. */
private val searchableSourceOrder = listOf(SourceId.SC, SourceId.ZING, SourceId.NCT)

/** Sources that are enabled, healthy and able to search right now. */
fun SourceRegistry.searchableSources(): List<SourceId> =
    searchableSourceOrder.filter { id -> available(id)?.capabilities?.contains(SourceCapability.SEARCH) == true }
