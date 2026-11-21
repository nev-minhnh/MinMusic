/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLocaleList
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.be_vietnam_pro_regular
import com.metrolist.shared.generated.resources.be_vietnam_pro_semibold
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.resolveAppLanguageTag
import com.metrolist.shared.state.GlobalYouTube
import org.jetbrains.compose.resources.Font

@Composable
fun currentAppLocaleList(): LocaleList = resolveAppLocaleList(Settings.shared.appLanguage, LocalLocaleList.current)

internal fun resolveAppLocaleList(
    configuredLanguage: String,
    systemLocales: LocaleList,
): LocaleList =
    if (configuredLanguage.isBlank() || configuredLanguage.equals(GlobalYouTube.SYSTEM, ignoreCase = true)) {
        systemLocales
    } else {
        LocaleList(
            resolveAppLanguageTag(configuredLanguage, systemLocales.map { it.toLanguageTag() }),
        )
    }

@Composable
expect fun localeAwareSystemFontFamily(languageTag: String): FontFamily

/** Be Vietnam Pro (SIL OFL 1.1), bundled in 400 and 600 only; see composeResources/files/licenses/bevietnampro. */
@Composable
fun beVietnamProFontFamily(): FontFamily {
    val regular = Font(Res.font.be_vietnam_pro_regular, FontWeight.Normal)
    val semiBold = Font(Res.font.be_vietnam_pro_semibold, FontWeight.SemiBold)
    return remember(regular, semiBold) { FontFamily(regular, semiBold) }
}

/**
 * MinMusic type scale: four sizes and two weights (design-system section 3). Hierarchy beyond that comes from
 * opacity, not extra weights or sizes.
 */
object MinType {
    val Label = 12.sp
    val LabelLineHeight = 18.sp
    val Body = 14.sp
    val BodyLineHeight = 20.sp
    val Title = 20.sp
    val TitleLineHeight = 28.sp
    val Display = 28.sp
    val DisplayLineHeight = 36.sp

    /** Tracking for 600-weight overlines (section labels). */
    val OverlineLetterSpacing = 0.2.sp

    /** Tabular figures for durations, times, EQ values, file sizes and ranks. */
    const val TabularFigures = "tnum"
}

/**
 * The app typography. [fontFamily] defaults to Be Vietnam Pro; on platforms that need a locale-specific system
 * family for the current language (CJK and similar on desktop/iOS) that family wins, and per-glyph fallback
 * covers the rest.
 */
@Composable
fun MetrolistTypography(
    localeList: LocaleList = currentAppLocaleList(),
    fontFamily: FontFamily? = null,
): Typography {
    val systemFont = localeAwareSystemFontFamily(localeList[0].toLanguageTag())
    val brandFont = beVietnamProFontFamily()
    val font = fontFamily ?: if (systemFont == FontFamily.Default) brandFont else systemFont

    fun style(
        size: TextUnit,
        lineHeight: TextUnit,
        weight: FontWeight,
        letterSpacing: TextUnit = 0.sp,
    ) = TextStyle(
        fontFamily = font,
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        localeList = localeList,
    )

    val display = style(MinType.Display, MinType.DisplayLineHeight, FontWeight.SemiBold)
    val title = style(MinType.Title, MinType.TitleLineHeight, FontWeight.SemiBold)
    val bodyStrong = style(MinType.Body, MinType.BodyLineHeight, FontWeight.SemiBold)
    val body = style(MinType.Body, MinType.BodyLineHeight, FontWeight.Normal)
    val label = style(MinType.Label, MinType.LabelLineHeight, FontWeight.Normal)
    val labelStrong = style(MinType.Label, MinType.LabelLineHeight, FontWeight.SemiBold)

    // M3 slots collapse onto the four MinMusic sizes (label 12, body 14, title 20, display 28).
    return Typography(
        fontFamily = font,
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = display,
        headlineMedium = display,
        headlineSmall = display,
        titleLarge = title,
        titleMedium = bodyStrong,
        titleSmall = bodyStrong,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = label,
        labelLarge = bodyStrong,
        labelMedium = labelStrong,
        labelSmall = label,
    )
}
