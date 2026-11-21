/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.utils

import java.io.File

object XdgUtils {
    // MinMusic on Windows keeps its own folders (%APPDATA%\MinMusic, %LOCALAPPDATA%\MinMusic) so it never
    // reads or moves the data of a Metrolist desktop installation on the same account.
    private const val WINDOWS_APP_DIR = "MinMusic"

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun windowsHome(
        variable: String,
        fallback: String,
        child: String,
    ): File {
        val base =
            System.getenv(variable)?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isAbsolute)
                ?: File(System.getProperty("user.home"), fallback)
        return File(File(base, WINDOWS_APP_DIR), child)
    }

    fun getXdgDataHome(): File {
        val env = System.getenv("XDG_DATA_HOME")
        return if (env != null && File(env).isAbsolute) {
            File(env)
        } else if (isWindows) {
            windowsHome("LOCALAPPDATA", "AppData/Local", "data")
        } else {
            File(System.getProperty("user.home"), ".local/share")
        }
    }

    fun getXdgCacheHome(): File {
        val env = System.getenv("XDG_CACHE_HOME")
        return if (env != null && File(env).isAbsolute) {
            File(env)
        } else if (isWindows) {
            windowsHome("LOCALAPPDATA", "AppData/Local", "cache")
        } else {
            File(System.getProperty("user.home"), ".cache")
        }
    }

    fun getXdgStateHome(): File {
        val env = System.getenv("XDG_STATE_HOME")
        return if (env != null && File(env).isAbsolute) {
            File(env)
        } else if (isWindows) {
            windowsHome("LOCALAPPDATA", "AppData/Local", "state")
        } else {
            File(System.getProperty("user.home"), ".local/state")
        }
    }

    fun getXdgConfigHome(): File {
        val env = System.getenv("XDG_CONFIG_HOME")
        return if (env != null && File(env).isAbsolute) {
            File(env)
        } else if (isWindows) {
            windowsHome("APPDATA", "AppData/Roaming", "config")
        } else {
            File(System.getProperty("user.home"), ".config")
        }
    }
}
