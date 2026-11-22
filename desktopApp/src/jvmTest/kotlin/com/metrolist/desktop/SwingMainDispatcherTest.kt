package com.metrolist.desktop

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * desktopCore is on this module's classpath; without -Dminmusic.sidecar=true its CoreMain dispatcher must stay out of the way.
 * kotlinx-coroutines-test is on the test classpath and wraps Dispatchers.Main in TestMainDispatcher, so the check picks the
 * factory Dispatchers.Main would use in the app: the highest-priority one that is not the test factory.
 */
@OptIn(InternalCoroutinesApi::class)
class SwingMainDispatcherTest {
    @Test
    fun mainDispatcherIsSwingNotCoreMain() {
        assertFalse(System.getProperty("minmusic.sidecar") == "true", "the v1 app must not run with the sidecar flag")
        val factories =
            ServiceLoader
                .load(MainDispatcherFactory::class.java, MainDispatcherFactory::class.java.classLoader)
                .toList()
                .filterNot { it::class.java.name.contains("TestMainDispatcher") }
        val winner = factories.maxByOrNull { it.loadPriority }
        val names = factories.map { "${it::class.java.name}=${it.loadPriority}" }
        assertTrue(winner != null && winner::class.java.name.contains("Swing"), "main dispatcher factories: $names")
        assertTrue(factories.any { it::class.java.name.contains("CoreMain") }, "CoreMainDispatcherFactory is registered: $names")
    }
}
