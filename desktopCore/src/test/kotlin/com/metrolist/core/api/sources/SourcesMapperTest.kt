package com.metrolist.core.api.sources

import com.metrolist.desktop.source.DesktopSourceLoginException
import com.metrolist.shared.source.SourceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcesMapperTest {
    private fun row(
        id: SourceId,
        enabled: Boolean = true,
        degraded: Boolean = false,
        signedIn: Boolean? = null,
        vip: Boolean? = null,
        account: String? = null,
    ) = SourceRow(id, enabled, degraded, signedIn, vip, account)

    @Test
    fun theFourSourcesWithoutLocal() {
        assertEquals(listOf("yt", "sc", "zing", "nct"), SourcesMapper.IDS.map { it.key })
    }

    @Test
    fun statusFollowsEnabledThenHealth() {
        assertEquals("off", SourcesMapper.status(enabled = false, degraded = false))
        assertEquals("off", SourcesMapper.status(enabled = false, degraded = true))
        assertEquals("degraded", SourcesMapper.status(enabled = true, degraded = true))
        assertEquals("ok", SourcesMapper.status(enabled = true, degraded = false))
    }

    @Test
    fun maxQualityNeverClaimsMoreThanTheAccountGets() {
        assertEquals("Opus 160", SourcesMapper.maxQuality(SourceId.YT, vip = true).label)
        assertEquals("AAC 160", SourcesMapper.maxQuality(SourceId.SC, vip = true).label)
        assertEquals("128", SourcesMapper.maxQuality(SourceId.ZING, vip = false).label)
        assertEquals("320", SourcesMapper.maxQuality(SourceId.ZING, vip = true).label)
        assertEquals("128", SourcesMapper.maxQuality(SourceId.NCT, vip = false).label)
        assertEquals("320", SourcesMapper.maxQuality(SourceId.NCT, vip = true).label)
    }

    @Test
    fun onlyZingAndNctHaveTheirOwnLogin() {
        assertEquals(listOf(false, false, true, true), SourcesMapper.IDS.map(SourcesMapper::canLogin))
    }

    @Test
    fun itemOmitsUnknownFieldsAndKeepsTheContractFields() {
        val sc = SourcesMapper.item(row(SourceId.SC, degraded = true)).jsonObject
        assertEquals("sc", sc["id"]!!.jsonPrimitive.content)
        assertEquals("degraded", sc["status"]!!.jsonPrimitive.content)
        assertNull(sc["signedIn"])
        assertNull(sc["vip"])
        assertNull(sc["account"])
        assertEquals("false", sc["canLogin"]!!.jsonPrimitive.content)
        assertEquals("AAC 160", sc["maxQuality"]!!.jsonObject["label"]!!.jsonPrimitive.content)

        val nct = SourcesMapper.item(row(SourceId.NCT, signedIn = true, vip = true, account = "tester")).jsonObject
        assertEquals("true", nct["vip"]!!.jsonPrimitive.content)
        assertEquals("tester", nct["account"]!!.jsonPrimitive.content)
        assertEquals("320", nct["maxQuality"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("NhacCuaTui", nct["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun listCarriesPriorityAndFlags() {
        val json = SourcesMapper.list(SourcesMapper.IDS.map { row(it) }, listOf("yt", "zing", "nct", "sc"), autoBest = true, autoFallback = false)
        assertEquals(4, json["items"]!!.jsonArray.size)
        assertEquals(listOf("yt", "zing", "nct", "sc"), (json["priority"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("true", json["autoBest"]!!.jsonPrimitive.content)
        assertEquals("false", json["autoFallback"]!!.jsonPrimitive.content)
    }

    @Test
    fun priorityMustBeAPermutation() {
        assertTrue(SourcesMapper.isValidPriority(listOf("nct", "sc", "yt", "zing")))
        assertFalse(SourcesMapper.isValidPriority(listOf("yt", "yt", "zing", "nct")))
        assertFalse(SourcesMapper.isValidPriority(listOf("yt")))
        assertFalse(SourcesMapper.isValidPriority(listOf("yt", "sc", "zing", "local")))
        assertFalse(SourcesMapper.isValidPriority(listOf("yt", "sc", "zing", "nct", "yt")))
    }

    @Test
    fun parseIdRejectsLocalAndUnknown() {
        assertEquals(SourceId.ZING, SourcesMapper.parseId("zing"))
        assertNull(SourcesMapper.parseId("local"))
        assertNull(SourcesMapper.parseId("nope"))
        assertNull(SourcesMapper.parseId(null))
    }

    @Test
    fun accountsRefreshEveryTenMinutes() {
        assertTrue(SourcesMapper.needsRefresh(null, 1_000))
        assertFalse(SourcesMapper.needsRefresh(1_000, 1_000 + SourcesMapper.ACCOUNT_TTL_MS - 1))
        assertTrue(SourcesMapper.needsRefresh(1_000, 1_000 + SourcesMapper.ACCOUNT_TTL_MS))
    }

    @Test
    fun loginFailuresMapToApiCodes() {
        assertEquals("unauthorized" to false, SourcesMapper.loginError(DesktopSourceLoginException.Reason.AUTH_FAILED))
        assertEquals("network" to true, SourcesMapper.loginError(DesktopSourceLoginException.Reason.NETWORK))
        assertEquals("not_signed_in" to false, SourcesMapper.loginError(DesktopSourceLoginException.Reason.NOT_SIGNED_IN))
        assertEquals("cancelled" to false, SourcesMapper.loginError(DesktopSourceLoginException.Reason.CANCELLED))
        assertEquals("internal" to false, SourcesMapper.loginError(DesktopSourceLoginException.Reason.UNKNOWN))
    }

    // --- SourcePrefsStore ----------------------------------------------------------------------------------------

    private fun tempFile(): File = Files.createTempDirectory("source-prefs-test").resolve("source-prefs.json").toFile()

    @Test
    fun prefsDefaultsThenRoundTrip() {
        val file = tempFile()
        val store = SourcePrefsStore(file)
        assertEquals(listOf("yt", "zing", "nct", "sc"), store.read().priority)
        assertTrue(store.read().autoFallback)
        store.update { it.copy(priority = listOf("sc", "nct", "zing", "yt"), autoFallback = false, trackOverrides = mapOf("abc" to TrackOverride("zing", "zing:1"))) }
        val reread = SourcePrefsStore(file).read()
        assertEquals(listOf("sc", "nct", "zing", "yt"), reread.priority)
        assertFalse(reread.autoFallback)
        assertEquals(TrackOverride("zing", "zing:1"), reread.trackOverrides["abc"])
        assertFalse(File(file.parentFile, "source-prefs.json.tmp").exists(), "the temp file is moved over the real one")
    }

    @Test
    fun damagedPrefsFallBackToDefaults() {
        val file = tempFile()
        file.writeText("{not json")
        assertEquals(listOf("yt", "zing", "nct", "sc"), SourcePrefsStore(file).read().priority)
        file.writeText("{\"priority\":[\"yt\",\"yt\"],\"autoFallback\":false}")
        val read = SourcePrefsStore(file).read()
        assertEquals(listOf("yt", "zing", "nct", "sc"), read.priority)
        assertFalse(read.autoFallback)
    }
}
