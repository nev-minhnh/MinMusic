package com.metrolist.shared.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.metrolist.shared.ui.haptics.MetrolistHapticsProvider

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetrolistTheme(
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = minMusicColorScheme(useDarkTheme)

    val motionScheme = remember { MotionScheme.expressive() }
    MaterialExpressiveTheme(
        colorScheme = animateColorScheme(colors, motionScheme),
        motionScheme = motionScheme,
        typography = MetrolistTypography(),
        shapes = MetrolistShapes,
        content = {
            CompositionLocalProvider(LocalBrand provides MinMusicBrand.of(useDarkTheme)) {
                MetrolistHapticsProvider(content)
            }
        },
    )
}
