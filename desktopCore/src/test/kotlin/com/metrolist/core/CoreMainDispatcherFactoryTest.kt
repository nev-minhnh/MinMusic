package com.metrolist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreMainDispatcherFactoryTest {
    @Test
    fun sidecarPropertyOnWinsOverEveryOtherFactory() {
        assertEquals(Int.MAX_VALUE, CoreMainDispatcherFactory.priorityFor("true"))
    }

    @Test
    fun sidecarPropertyOffOrUnsetLosesToEveryOtherFactory() {
        assertEquals(Int.MIN_VALUE, CoreMainDispatcherFactory.priorityFor(null))
        assertEquals(Int.MIN_VALUE, CoreMainDispatcherFactory.priorityFor("false"))
        assertEquals(Int.MIN_VALUE, CoreMainDispatcherFactory.priorityFor(""))
    }

    @Test
    fun loadPriorityReadsTheSystemPropertyAtCallTime() {
        val previous = System.getProperty(CoreMainDispatcherFactory.SIDECAR_PROPERTY)
        try {
            System.setProperty(CoreMainDispatcherFactory.SIDECAR_PROPERTY, "true")
            assertEquals(Int.MAX_VALUE, CoreMainDispatcherFactory().loadPriority)
            System.clearProperty(CoreMainDispatcherFactory.SIDECAR_PROPERTY)
            assertEquals(Int.MIN_VALUE, CoreMainDispatcherFactory().loadPriority)
            assertTrue(Int.MIN_VALUE < 0)
        } finally {
            if (previous == null) {
                System.clearProperty(CoreMainDispatcherFactory.SIDECAR_PROPERTY)
            } else {
                System.setProperty(CoreMainDispatcherFactory.SIDECAR_PROPERTY, previous)
            }
        }
    }
}
