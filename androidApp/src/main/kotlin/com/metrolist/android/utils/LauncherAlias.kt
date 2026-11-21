/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * MinMusic has a single launcher entry (`.MainActivityOriginal`, kept for stable home-screen shortcuts).
 * Older builds could disable it while an alternate icon was selected; component states survive updates, so
 * re-enable it if a previous icon choice left it disabled.
 */
object LauncherAlias {
    private const val MANIFEST_PACKAGE = "com.metrolist.android"
    private const val ALIAS_NAME = "MainActivityOriginal"

    fun ensureEnabled(context: Context) {
        val component = ComponentName(context, "$MANIFEST_PACKAGE.$ALIAS_NAME")
        val pm = context.packageManager
        if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
