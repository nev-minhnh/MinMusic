package com.metrolist.core.api.download

import kotlin.test.Test
import kotlin.test.assertEquals

class MimeLabelTest {
    @Test
    fun labelsTheStoredFormats() {
        assertEquals("FLAC", MimeLabel.label("audio/flac"))
        assertEquals("AAC", MimeLabel.label("audio/mp4"))
        assertEquals("AAC", MimeLabel.label("audio/aac"))
        assertEquals("Opus", MimeLabel.label("audio/webm"))
        assertEquals("Opus", MimeLabel.label("audio/ogg"))
        assertEquals("Opus", MimeLabel.label("audio/opus"))
        assertEquals("MP3", MimeLabel.label("audio/mpeg"))
        assertEquals("MP3", MimeLabel.label("audio/mp3"))
    }

    @Test
    fun unknownAndMissingAreOther() {
        assertEquals("Other", MimeLabel.label(null))
        assertEquals("Other", MimeLabel.label(""))
        assertEquals("Other", MimeLabel.label("application/octet-stream"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals("FLAC", MimeLabel.label("Audio/FLAC"))
    }
}
