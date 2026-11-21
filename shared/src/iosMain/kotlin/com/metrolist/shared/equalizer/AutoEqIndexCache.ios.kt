/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

// iOS keeps the AutoEq index in memory only.
internal actual fun autoEqCacheDirectory(): String? = null

internal actual fun readAutoEqIndexCache(): Pair<String, Long>? = null

internal actual fun writeAutoEqIndexCache(content: String) = Unit
