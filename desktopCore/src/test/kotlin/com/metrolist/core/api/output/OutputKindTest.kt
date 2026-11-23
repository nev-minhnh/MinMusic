package com.metrolist.core.api.output

import com.metrolist.shared.equalizer.EqualizerOutputKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputKindTest {
    @Test
    fun kindByNameKeyword() {
        assertEquals("bluetooth", outputKindOf("Headset (WH-1000XM4 Bluetooth)"))
        assertEquals("bluetooth", outputKindOf("Tai nghe BT"))
        assertEquals("usb", outputKindOf("Speakers (USB Audio Device)"))
        assertEquals("wired", outputKindOf("Headphones (Realtek Audio)"))
        assertEquals("wired", outputKindOf("Headset Earphone"))
        assertEquals("speaker", outputKindOf("Speakers (Realtek High Definition Audio)"))
        assertEquals("speaker", outputKindOf("Loa ngoai"))
        assertEquals("other", outputKindOf("Primary Sound Driver"))
        assertEquals("other", outputKindOf("Digital Output (S/PDIF)"))
    }

    @Test
    fun keywordsMatchWholeWords() {
        assertEquals("other", outputKindOf("Cloak output"))
        assertEquals("other", outputKindOf("Abt device"))
    }

    @Test
    fun equalizerKinds() {
        assertEquals(EqualizerOutputKind.BLUETOOTH, equalizerKindOf("bluetooth"))
        assertEquals(EqualizerOutputKind.USB, equalizerKindOf("usb"))
        assertEquals(EqualizerOutputKind.WIRED, equalizerKindOf("wired"))
        assertEquals(EqualizerOutputKind.SPEAKER, equalizerKindOf("speaker"))
        assertEquals(EqualizerOutputKind.OTHER, equalizerKindOf("other"))
        assertEquals(EqualizerOutputKind.OTHER, equalizerKindOf("whatever"))
    }

    @Test
    fun defaultIsThePrimarySoundDriverElseTheFirst() {
        assertEquals("Primary Sound Driver", defaultOutputName(listOf("Speakers", "Primary Sound Driver")))
        assertEquals("Speakers", defaultOutputName(listOf("Speakers", "Headphones")))
        assertNull(defaultOutputName(emptyList()))
    }

    @Test
    fun currentIsTheChosenDeviceWhileItExists() {
        val names = listOf("Primary Sound Driver", "Speakers", "Headphones")
        assertEquals("Headphones", currentOutputId(names, "Headphones"))
        assertEquals("Primary Sound Driver", currentOutputId(names, "Unplugged"))
        assertEquals("Primary Sound Driver", currentOutputId(names, null))
    }

    @Test
    fun deviceListFlagsOneDefaultAndDropsDuplicates() {
        val devices = outputDevices(listOf("Speakers", "Primary Sound Driver", "Speakers"))
        assertEquals(listOf("Speakers", "Primary Sound Driver"), devices.map { it.id })
        assertTrue(devices[1].default)
        assertFalse(devices[0].default)
        assertEquals("speaker", devices[0].kind)
    }
}
