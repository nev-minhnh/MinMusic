/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import com.metrolist.shared.utils.XdgUtils
import java.io.File

internal actual fun autoEqCacheDirectory(): String? = File(XdgUtils.getXdgCacheHome(), "metrolist/autoeq").absolutePath
