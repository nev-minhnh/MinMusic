package com.metrolist.core.api.settings

import com.metrolist.core.server.badRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/** Checks a `settings.set` value against its schema entry and returns the plain value for `Settings.setRaw`. Pure. */
object Validation {
    /** Throws `bad_request` when [value] does not fit [entry] (type, enum membership, min/max, secrets). */
    fun coerce(
        entry: SettingEntry,
        value: JsonElement,
    ): Any {
        val key = entry.key
        val p = value as? JsonPrimitive
        return when (entry.type) {
            "secret" -> badRequest("$key: secret, use its dedicated method")
            "bool" -> p?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: badRequest("$key: boolean required")
            "int", "float" -> {
                val d = p?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: badRequest("$key: number required")
                if (entry.type == "int" && d != floor(d)) badRequest("$key: integer required")
                entry.range?.let {
                    if (d < it.min) badRequest("$key: below ${SettingJson.number(it.min)}")
                    if (d > it.max) badRequest("$key: above ${SettingJson.number(it.max)}")
                }
                if (entry.type == "int") d.toLong() else d
            }
            "enum" -> {
                val s = p?.takeIf { it.isString }?.content
                if (s == null || entry.enumValues?.contains(s) != true) badRequest("$key: one of ${entry.enumValues?.joinToString("|")}")
                s
            }
            else -> p?.takeIf { it.isString }?.content ?: badRequest("$key: string required")
        }
    }
}
