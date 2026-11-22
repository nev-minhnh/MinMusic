package com.metrolist.desktop

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** desktopCore is on this module's classpath; without -Dminmusic.sidecar=true its CoreMain dispatcher must stay out of the way. */
class SwingMainDispatcherTest {
    @Test
    fun mainDispatcherIsSwingNotCoreMain() {
        assertFalse(System.getProperty("minmusic.sidecar") == "true", "the v1 app must not run with the sidecar flag")
        val main = Dispatchers.Main
        assertFalse(main.toString().contains("CoreMain"), "Dispatchers.Main is $main")
        assertTrue(
            main::class.java.name.contains("Swing") || main.toString().contains("Swing"),
            "Dispatchers.Main is ${main::class.java.name} / $main",
        )
    }
}
