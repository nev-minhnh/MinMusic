/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

/**
 * One registered preference, as recorded by the `preference(...)` delegate provider in [Settings].
 *
 * - [default] is a plain value: String, Boolean, Int, Long or Float; for an enum preference it is the entry name.
 * - [enumValues] lists the entry names (declaration order, so the index is the stored ordinal), null when not an enum.
 *
 * Read the full list through [Settings.registry], a value through [Settings.getRaw] and write through [Settings.setRaw].
 */
data class SettingMeta(
    val key: String,
    val type: SettingType,
    val default: Any?,
    val enumValues: List<String>?,
)
