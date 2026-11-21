/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.metrolist.android.utils.AppFontManager
import com.metrolist.android.utils.QuickFontManager
import com.metrolist.shared.settings.FontOption
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.theme.MetrolistTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App typography with the user's font override applied; with no override it stays Be Vietnam Pro. */
@Composable
fun getAppTypography(): Typography {
    val context = LocalContext.current
    val appFont = Settings.shared.appFont
    val customFontFile = Settings.shared.androidCustomFontFile
    val activeFontId = Settings.shared.androidActiveFontId
    val overrideFont by produceState<FontFamily?>(null, appFont, customFontFile, activeFontId) {
        value = withContext(Dispatchers.IO) { resolveFontFamily(context, appFont, customFontFile, activeFontId) }
    }
    return MetrolistTypography(fontFamily = overrideFont)
}

/** Returns the user's chosen font family, or null to use the bundled MinMusic font. */
private fun resolveFontFamily(
    context: Context,
    appFont: FontOption,
    customFontFile: String,
    activeFontId: String,
): FontFamily? {
    when (activeFontId) {
        // "System"/reset means the app default, which is now the bundled Be Vietnam Pro.
        QuickFontManager.SYSTEM_ID -> {
            return null
        }

        QuickFontManager.CUSTOM_ID -> {
            return AppFontManager.loadFontFamily(context, customFontFile).takeUnless { it == FontFamily.Default }
        }

        else -> {
            if (QuickFontManager.isSupportedId(activeFontId)) {
                return QuickFontManager.loadFontFamily(context, activeFontId).takeUnless { it == FontFamily.Default }
            }
        }
    }

    if (activeFontId.isNotEmpty()) return null

    if (appFont == FontOption.GOOGLE_SANS) {
        return QuickFontManager.loadFontFamily(context, QuickFontManager.GOOGLE_SANS_ID).takeUnless { it == FontFamily.Default }
    }

    if (customFontFile.isNotBlank()) {
        val customFont = AppFontManager.loadFontFamily(context, customFontFile)
        if (customFont != FontFamily.Default) return customFont
    }

    return null
}
