/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.content

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/** The Compose half of `HomeState.rememberListState` (desktopCore keeps the saved index and offset as plain ints). */
@Composable
fun HomeState.rememberListState(key: String): LazyListState {
    val (savedIndex, savedOffset) = restoreListScroll(key)
    val state = remember(key) { LazyListState(savedIndex, savedOffset) }

    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                saveListScroll(key, index, offset)
            }
    }

    DisposableEffect(key) {
        onDispose {
            saveListScroll(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
    }

    return state
}
