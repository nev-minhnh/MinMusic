package com.metrolist.core.api.settings

import com.metrolist.shared.settings.SettingMeta
import com.metrolist.shared.settings.SettingType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

/** One `settings.schema` entry (api.md 3.10). */
data class SettingEntry(
    val key: String,
    /** `bool`, `int`, `float`, `string`, `enum`, `secret` or `json`. */
    val type: String,
    val default: JsonElement,
    val enumValues: List<String>?,
    val range: Range?,
    val platform: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("key", key)
            put("type", type)
            put("default", default)
            if (enumValues != null) put("enumValues", JsonArray(enumValues.map(::JsonPrimitive)))
            if (range != null) {
                put("min", SettingJson.number(range.min))
                put("max", SettingJson.number(range.max))
                put("step", SettingJson.number(range.step))
            }
            put("platform", platform)
        }
}

/** Turns the shared registry into schema entries. Pure. */
object SchemaMapper {
    fun typeOf(meta: SettingMeta): String =
        when {
            meta.key in SECRET_KEYS -> "secret"
            meta.enumValues != null || meta.key in StringEnums.table -> "enum"
            meta.type == SettingType.BOOLEAN -> "bool"
            meta.type == SettingType.INT || meta.type == SettingType.LONG -> "int"
            meta.type == SettingType.FLOAT -> "float"
            meta.key.endsWith("Json") -> "json"
            else -> "string"
        }

    fun entryOf(meta: SettingMeta): SettingEntry {
        val type = typeOf(meta)
        val values = if (type == "enum") meta.enumValues ?: StringEnums.table[meta.key] else null
        // The real default of a secret is never returned.
        val default = if (type == "secret") SettingJson.SECRET_UNSET else SettingJson.toJson(meta.default)
        val range = if (type == "int" || type == "float") Ranges.table[meta.key] else null
        return SettingEntry(meta.key, type, default, values, range, PlatformPrefix.platformOf(meta.key))
    }

    /** All entries, sorted by key (the order of the mock fixture). */
    fun entries(registry: List<SettingMeta>): List<SettingEntry> = registry.map(::entryOf).sortedBy { it.key }
}

/** Raw value <-> JSON, and the masking of secrets. Pure. */
object SettingJson {
    /** What a client sees for a secret that holds nothing. */
    val SECRET_UNSET: JsonObject = buildJsonObject { put("set", false) }

    fun number(d: Double): JsonPrimitive = if (d == floor(d) && abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    /** A registry or [com.metrolist.shared.settings.Settings.getRaw] value as JSON. Floats keep their decimal form (0.1f is 0.1). */
    fun toJson(raw: Any?): JsonElement =
        when (raw) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(raw)
            is String -> JsonPrimitive(raw)
            is Int -> JsonPrimitive(raw)
            is Long -> JsonPrimitive(raw)
            is Float -> JsonPrimitive(raw.toString().toDouble())
            is Double -> JsonPrimitive(raw)
            else -> JsonPrimitive(raw.toString())
        }

    /** The value a client may see: secrets become `{set: nonBlank}`. */
    fun wire(
        key: String,
        raw: Any?,
    ): JsonElement =
        if (key in SECRET_KEYS) {
            buildJsonObject { put("set", raw is String && raw.isNotBlank() || raw == true) }
        } else {
            toJson(raw)
        }
}
