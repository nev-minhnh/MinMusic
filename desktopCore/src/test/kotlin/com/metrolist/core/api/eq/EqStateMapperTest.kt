package com.metrolist.core.api.eq

import com.metrolist.shared.equalizer.CrossfeedPreset
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.FilterType
import com.metrolist.shared.equalizer.ParametricEqBand
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqStateMapperTest {
    private fun input(
        profile: EqualizerProfile?,
        saved: List<EqualizerProfile> = emptyList(),
        devices: Map<String, String> = emptyMap(),
    ) = EqStateInput(
        enabled = true,
        profile = profile,
        savedProfiles = saved,
        autoPreamp = true,
        limiterEnabled = false,
        limiterReductionDb = 1.5f,
        canUndo = true,
        canRedo = false,
        deviceProfiles = devices,
    )

    private val profile =
        EqualizerProfile(
            id = "custom_1",
            name = "Mine",
            bands = listOf(ParametricEqBand(105.0, 6.5, 0.7, FilterType.LSC), ParametricEqBand(1000.0, -2.0)),
            preamp = -5.8,
            outputGainDb = -1.5,
            bassDb = 3.0,
            trebleDb = -2.0,
            crossfeedEnabled = true,
            crossfeedPreset = CrossfeedPreset.CHU_MOY,
            stereoWidth = 1.2,
        )

    @Test
    fun mapsEveryField() {
        val json = EqStateMapper.toJson(input(profile, listOf(profile), mapOf("dev1" to "custom_1")))
        assertEquals("custom_1", json["profileId"]!!.jsonPrimitive.content)
        assertEquals(32, json["maxBands"]!!.jsonPrimitive.int)
        assertEquals(-5.8, json["preampDb"]!!.jsonPrimitive.double)
        assertTrue(json["autoPreamp"]!!.jsonPrimitive.boolean)
        assertTrue(json["canUndo"]!!.jsonPrimitive.boolean)
        assertFalse(json["canRedo"]!!.jsonPrimitive.boolean)
        assertEquals(1.5, json["limiterReductionDb"]!!.jsonPrimitive.double)
        val bands = json["bands"]!!.jsonArray
        assertEquals(2, bands.size)
        assertEquals("LSC", bands[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(105.0, bands[0].jsonObject["freq"]!!.jsonPrimitive.double)
        assertEquals(6.5, bands[0].jsonObject["gain"]!!.jsonPrimitive.double)
        assertEquals(0.7, bands[0].jsonObject["q"]!!.jsonPrimitive.double)
        val fx = json["effects"]!!.jsonObject
        assertEquals(3.0, fx["bassDb"]!!.jsonPrimitive.double)
        assertEquals(-2.0, fx["trebleDb"]!!.jsonPrimitive.double)
        assertEquals(120.0, fx["widthPct"]!!.jsonPrimitive.double)
        assertEquals("chuMoy", fx["crossfeed"]!!.jsonPrimitive.content)
        assertFalse(fx["limiter"]!!.jsonPrimitive.boolean)
        assertEquals(-1.5, fx["outputDb"]!!.jsonPrimitive.double)
        val device = json["deviceProfiles"]!!.jsonArray.single().jsonObject
        assertEquals("dev1", device["deviceId"]!!.jsonPrimitive.content)
        assertEquals("dev1", device["deviceName"]!!.jsonPrimitive.content)
        assertEquals("custom_1", device["profileId"]!!.jsonPrimitive.content)
    }

    @Test
    fun presetsComeFirstAndBuiltinFollowsTheIdPrefix() {
        val autoeq = profile.copy(id = "autoeq:oratory1990/x", name = "HD 600")
        val list = EqStateMapper.profileList(listOf(profile, autoeq))
        assertEquals("preset_flat", list.first().id)
        assertTrue(list.take(6).all { it.builtin })
        assertEquals(listOf(false, true), list.drop(6).map { it.builtin })
        val json = EqStateMapper.toJson(input(profile, listOf(profile, autoeq)))
        val rows = json["profiles"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8, rows.size)
        assertTrue(rows.first()["builtin"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun noActiveProfileGivesNeutralState() {
        val json = EqStateMapper.toJson(input(null))
        assertEquals(JsonNull, json["profileId"])
        assertEquals(0, json["bands"]!!.jsonArray.size)
        val fx = json["effects"]!!.jsonObject
        assertEquals("off", fx["crossfeed"]!!.jsonPrimitive.content)
        assertEquals(100.0, fx["widthPct"]!!.jsonPrimitive.double)
        assertEquals(0.0, fx["outputDb"]!!.jsonPrimitive.double)
    }

    @Test
    fun crossfeedOffWhenDisabledEvenWithAPreset() {
        val json = EqStateMapper.toJson(input(profile.copy(crossfeedEnabled = false)))
        assertEquals("off", json["effects"]!!.jsonObject["crossfeed"]!!.jsonPrimitive.content)
    }

    @Test
    fun nonFiniteLimiterReductionBecomesZero() {
        val json = EqStateMapper.toJson(input(profile).copy(limiterReductionDb = Float.NaN))
        assertEquals(0.0, json["limiterReductionDb"]!!.jsonPrimitive.double)
    }
}
