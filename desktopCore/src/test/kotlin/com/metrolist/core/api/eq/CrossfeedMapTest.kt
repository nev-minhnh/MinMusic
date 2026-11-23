package com.metrolist.core.api.eq

import com.metrolist.shared.equalizer.CrossfeedPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrossfeedMapTest {
    @Test
    fun toApiNamesEveryPreset() {
        assertEquals("off", CrossfeedMap.toApi(false, CrossfeedPreset.JAN_MEIER))
        assertEquals("default", CrossfeedMap.toApi(true, CrossfeedPreset.DEFAULT))
        assertEquals("chuMoy", CrossfeedMap.toApi(true, CrossfeedPreset.CHU_MOY))
        assertEquals("janMeier", CrossfeedMap.toApi(true, CrossfeedPreset.JAN_MEIER))
    }

    @Test
    fun fromApiRoundTripsAndKeepsThePresetWhenOff() {
        for (preset in CrossfeedPreset.entries) {
            val parsed = CrossfeedMap.fromApi(CrossfeedMap.toApi(true, preset))
            assertEquals(CrossfeedMap.Crossfeed(true, preset), parsed)
        }
        assertEquals(CrossfeedMap.Crossfeed(false, null), CrossfeedMap.fromApi("off"))
    }

    @Test
    fun fromApiRejectsUnknownNames() {
        assertNull(CrossfeedMap.fromApi("nope"))
        assertNull(CrossfeedMap.fromApi("CHU_MOY"))
        assertNull(CrossfeedMap.fromApi(""))
    }
}
