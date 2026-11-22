/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.metrolist.desktop.utils.MatugenColors
import com.metrolist.desktop.utils.resolveDesktopSeedColor
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.theme.resolveThemeSeedColor

/** The theme seed colour half of the old UiState: it needs Compose Color, so it stays out of the headless desktopCore. */
object UiColors {
    internal var artworkSeedColor by mutableStateOf<Color?>(null)
    val seedColor: Color
        get() =
            MatugenColors.palette?.sourceColor ?: resolveDesktopSeedColor(
                configured =
                    resolveThemeSeedColor(
                        materialYouEnabled = Settings.shared.materialYouEnabled,
                        songArtworkColorsEnabled = Settings.shared.dynamicTheme,
                        selectedThemeColor = Settings.shared.selectedThemeColor,
                        artworkSeedColor = artworkSeedColor,
                    ),
                useSystemAccent = Settings.shared.materialYouEnabled && (!Settings.shared.dynamicTheme || artworkSeedColor == null),
            )
}
