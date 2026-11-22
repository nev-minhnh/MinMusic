package com.metrolist.core.api

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.source.sourceOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Shared card DTOs (api.md section 2) and small JSON helpers used by the browse, search, pages and queue areas.
 *
 * Compared with `YTItem.toItem()` in Dto.kt the card mappers produce the same fields:
 *  - album: id, title, artists, year (Int, only when numeric), art, source, kind ("single" / "ep" / "album");
 *  - artist: id, name (= title), art, subtitle (= subscribers), source;
 *  - playlist: id, title, subtitle (= subtitle ?: author), art, source, owner (= author), local, count (digits of songCount).
 * No field differs; `toItem()` just writes them straight into a JsonObject with the extra `t` discriminator.
 */

@Serializable
data class AlbumCard(
    val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val year: Int? = null,
    val art: Art? = null,
    val source: String,
    val kind: String,
)

@Serializable
data class ArtistCard(
    val id: String,
    val name: String,
    val art: Art? = null,
    val subtitle: String? = null,
    val source: String,
)

@Serializable
data class PlaylistCard(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val art: Art? = null,
    val source: String,
    val owner: String? = null,
    val local: Boolean,
    val count: Int? = null,
)

// Same conversion as `artOf` in Dto.kt (private there).
private fun artFrom(thumbnail: String?): Art? = thumbnail?.takeIf { it.isNotBlank() }?.let { Art(src = it) }

fun AlbumItem.toCard(): AlbumCard =
    AlbumCard(
        id = id,
        title = title,
        artists = artists.map { ArtistRef(it.id, it.name) },
        year = year?.toIntOrNull(),
        art = artFrom(thumbnail),
        source = sourceOf(id).key,
        kind = albumKind(type),
    )

/** "single" and "ep" are recognised from the YouTube album type text; anything else is an album. */
fun albumKind(type: String?): String =
    when {
        type?.contains("single", ignoreCase = true) == true -> "single"
        type?.equals("ep", ignoreCase = true) == true -> "ep"
        else -> "album"
    }

fun ArtistItem.toCard(): ArtistCard =
    ArtistCard(
        id = id,
        name = title,
        art = artFrom(thumbnail),
        subtitle = subscribers,
        source = sourceOf(id).key,
    )

fun PlaylistItem.toCard(): PlaylistCard =
    PlaylistCard(
        id = id,
        title = title,
        subtitle = subtitle ?: author,
        art = artFrom(thumbnail),
        source = sourceOf(id).key,
        owner = author,
        local = isLocal,
        count = songCount?.filter(Char::isDigit)?.toIntOrNull(),
    )

/** Serialises [card] and prepends the `t` discriminator (`album`, `artist`, `playlist`); `type` is the discriminator. */
fun cardJson(
    type: String,
    card: Any,
): JsonObject {
    val body: JsonObject =
        when (card) {
            is AlbumCard -> card.toJson().jsonObject
            is ArtistCard -> card.toJson().jsonObject
            is PlaylistCard -> card.toJson().jsonObject
            else -> throw IllegalArgumentException("not a card: ${card::class.java.name}")
        }
    return buildJsonObject {
        put("t", type)
        body.forEach { (key, value) -> put(key, value) }
    }
}

/** `base` with `extra` merged over it (keys of `extra` win). */
fun JsonObject.merged(extra: JsonObject): JsonObject = JsonObject(this + extra)

/** `JsonObject` from pairs, skipping null values. */
fun jsonOf(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

fun String?.json(): JsonElement? = this?.let { JsonPrimitive(it) }

/** Lower-case, dash-separated key for a section title that has no browse id. */
fun slug(title: String): String =
    title
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .ifEmpty { "section" }

/** api.md `Item`s of a mixed list; kinds without a wire shape (podcasts, browse tiles) are dropped. */
fun List<YTItem>.toItems(): List<JsonObject> = mapNotNull { it.toItem() }

/** One home/browse section as a [Shelf]; the key falls back to the position when the section has no browse id. */
fun HomeSection.toShelf(index: Int): Shelf =
    Shelf(
        key = browseId ?: "section-$index",
        title = title,
        subtitle = subtitle,
        layout = if (isListStyle) "rows" else "cards",
        items = items.toItems(),
    )

fun List<HomeSection>.toShelves(): List<Shelf> = mapIndexed { index, section -> section.toShelf(index) }

fun List<Shelf>.toShelvesJson(): JsonArray = JsonArray(map { it.toJson() })
