package com.metrolist.core.api.tracksource

import com.metrolist.core.api.Quality
import com.metrolist.core.api.sources.TrackOverride
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.source.QualityRanker
import com.metrolist.shared.source.SourceContentException
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.sourceUnavailable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TrackSourceLabelTest {
    @Test
    fun tiersMapToApiQualityLabels() {
        assertEquals(Quality("Lossless", true), TrackSourceLabels.quality(QualityRanker.Tier.LOSSLESS, null))
        assertEquals("320", TrackSourceLabels.quality(QualityRanker.Tier.HIGH, 320)!!.label)
        assertEquals("256", TrackSourceLabels.quality(QualityRanker.Tier.HIGH, 256)!!.label)
        assertEquals("Opus 160", TrackSourceLabels.quality(QualityRanker.Tier.OPUS_STANDARD, 160)!!.label)
        assertEquals("AAC 160", TrackSourceLabels.quality(QualityRanker.Tier.AAC_STANDARD, 160)!!.label)
        assertEquals("128", TrackSourceLabels.quality(QualityRanker.Tier.LOW, 128)!!.label)
        assertNull(TrackSourceLabels.quality(QualityRanker.Tier.UNKNOWN, null))
    }

    @Test
    fun refusalReasonsMapToHints() {
        assertEquals("vip_login", TrackSourceLabels.hint(SourceContentException.Reason.VIP_REQUIRED))
        assertEquals("region", TrackSourceLabels.hint(SourceContentException.Reason.GEO_BLOCKED))
        assertEquals("preview_only", TrackSourceLabels.hint(SourceContentException.Reason.PREVIEW_ONLY))
        assertNull(TrackSourceLabels.hint(SourceContentException.Reason.NOT_FOUND))
    }

    @Test
    fun theReasonIsFoundInTheCauseOfAStreamFailure() {
        val failure = sourceUnavailable(SourceId.NCT, SourceContentException.Reason.VIP_REQUIRED)
        assertEquals(SourceContentException.Reason.VIP_REQUIRED, TrackSourceLabels.reasonOf(failure))
        assertNull(TrackSourceLabels.reasonOf(IllegalStateException("other")))
    }

    @Test
    fun optionHasTheContractFields() {
        val vip = TrackSourceLabels.option(SourceId.NCT, "nct:1", Quality("128", false, 128), auto = false, available = false, hint = "vip_login").jsonObject
        assertEquals("nct", vip["source"]!!.jsonPrimitive.content)
        assertEquals("nct:1", vip["id"]!!.jsonPrimitive.content)
        assertEquals("false", vip["available"]!!.jsonPrimitive.content)
        assertEquals("vip_login", vip["hint"]!!.jsonPrimitive.content)
        assertEquals("128", vip["quality"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertNull(TrackSourceLabels.option(SourceId.YT, "x", Quality("Opus 160", false, 160), auto = true, available = true).jsonObject["hint"])
    }

    private fun song(id: String) = SongItem(id = id, title = id, thumbnail = null, artists = emptyList<ArtistTiny>(), album = null)

    @Test
    fun overridesReplaceOnlyKnownMatches() {
        val replacement = song("zing:1")
        val overrides = mapOf("a" to TrackOverride("zing", "zing:1"), "b" to TrackOverride("nct", "nct:9"))
        val input = listOf(song("a"), song("b"), song("c"))
        val out = TrackSourceOverrides.applyWith(input, overrides) { if (it.id == "zing:1") replacement else null }
        assertSame(replacement, out[0])
        assertSame(input[1], out[1], "an override whose song is unknown keeps the original")
        assertSame(input[2], out[2])
        assertSame(input, TrackSourceOverrides.applyWith(input, emptyMap()) { null })
    }
}
