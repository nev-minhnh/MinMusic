/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import com.metrolist.shared.youtube.playback.AndroidContextProvider
import java.io.File

internal actual fun autoEqCacheDirectory(): String? = File(AndroidContextProvider.applicationContext.cacheDir, "autoeq").absolutePath
