package com.metrolist.core.api.integrations

import com.metrolist.shared.api.ScrobbleApiException
import com.metrolist.shared.api.ScrobbleFailureDisposition
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class LastFmErrorMapTest {
    @Test
    fun lastFmErrorFourIsWrongCredentials() {
        val error = ScrobbleApiException("Invalid password", ScrobbleFailureDisposition.PERMANENT, apiErrorCode = 4)
        assertEquals("unauthorized" to false, LastFmErrors.lastFm(error))
    }

    @Test
    fun otherLastFmApiErrorsAreInternal() {
        val error = ScrobbleApiException("Service offline", ScrobbleFailureDisposition.RETRY, apiErrorCode = 11)
        assertEquals("internal" to false, LastFmErrors.lastFm(error))
        assertEquals("internal" to false, LastFmErrors.lastFm(IllegalStateException("Last.fm returned an invalid session")))
    }

    @Test
    fun ioAndTimeoutsAreNetworkAndRetryable() {
        assertEquals("network" to true, LastFmErrors.lastFm(IOException("unreachable")))
        assertEquals("network" to true, LastFmErrors.lastFm(HttpRequestTimeoutException("https://ws.audioscrobbler.com", 1000L)))
    }

    @Test
    fun listenBrainzDistinguishesNetworkFromAnInvalidToken() {
        assertEquals("network" to true, LastFmErrors.listenBrainz(IOException("down")))
        assertEquals("unauthorized" to false, LastFmErrors.listenBrainz(IllegalStateException("ListenBrainz token is invalid")))
        assertEquals("unauthorized" to false, LastFmErrors.listenBrainz(ScrobbleApiException("401", ScrobbleFailureDisposition.AUTHENTICATION)))
    }
}
