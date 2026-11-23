package com.metrolist.core.api.integrations

import com.metrolist.shared.api.ScrobbleApiException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException

/**
 * Maps a failed Last.fm / ListenBrainz call to an api error `(code, retryable)`. It copies the `lastFmLoginFailure` rule of
 * v1 (internal to the shared module): Last.fm error 4 is wrong credentials, I/O and timeouts are network, the rest internal.
 */
object LastFmErrors {
    fun lastFm(error: Throwable): Pair<String, Boolean> =
        when {
            error is ScrobbleApiException && error.apiErrorCode == INVALID_CREDENTIALS -> "unauthorized" to false
            isNetwork(error) -> "network" to true
            else -> "internal" to false
        }

    /** ListenBrainz: an unreachable service is network; any other failure of the token check means the token is not valid. */
    fun listenBrainz(error: Throwable): Pair<String, Boolean> = if (isNetwork(error)) "network" to true else "unauthorized" to false

    private fun isNetwork(error: Throwable): Boolean = error is IOException || error is HttpRequestTimeoutException

    private const val INVALID_CREDENTIALS = 4
}
