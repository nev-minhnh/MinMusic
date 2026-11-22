package com.metrolist.android.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.exclude
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val TabTopBarHeight = 64.dp

/**
 * Height of the top-level top bar. Phones keep [TabTopBarHeight]; tablets use the compact 56 dp bar
 * (48 dp when the window is short, e.g. a landscape 8"/11" tablet), matching the tablet mockups.
 */
val LocalTabTopBarHeight = staticCompositionLocalOf { TabTopBarHeight }

/** Tablet top bar height for the given window height. */
fun tabletTopBarHeight(windowHeight: Dp): Dp = if (windowHeight < 900.dp) 48.dp else 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun tabContentTopPadding(): Dp =
    TopAppBarDefaults.windowInsets
        .exclude(WindowInsets.captionBar)
        .asPaddingValues()
        .calculateTopPadding() + LocalTabTopBarHeight.current
