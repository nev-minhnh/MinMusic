package com.metrolist.desktop.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_language_system
import com.metrolist.shared.ui.settings.SettingsChoice
import org.jetbrains.compose.resources.stringResource

@Composable
fun appLanguageChoices(): List<SettingsChoice> =
    // Each locale name parse throws and catches internally; Settings recomposes this list often.
    remember { DesktopLocaleManager.languages() }.map { language ->
        SettingsChoice(
            language.tag,
            when {
                language.isSystem -> stringResource(Res.string.app_language_system)
                else -> requireNotNull(language.displayName)
            },
        )
    }
