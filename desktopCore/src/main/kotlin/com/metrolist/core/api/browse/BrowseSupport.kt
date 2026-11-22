package com.metrolist.core.api.browse

import com.metrolist.core.server.ApiException
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.SourceRegistry
import com.metrolist.shared.source.SourceUnavailableException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.context.GlobalContext

/** The source registry from Koin (`GlobalContext.get().get<SourceRegistry>()`); call only after `backendReady()`. */
fun sourceRegistry(): SourceRegistry = GlobalContext.get().get<SourceRegistry>()

/** api.md 1.5: `source_disabled` or `source_degraded`, with `details.sourceId`. */
fun sourceUnavailable(
    source: SourceId,
    reason: SourceUnavailableException.Reason,
): ApiException =
    ApiException(
        code = if (reason == SourceUnavailableException.Reason.DISABLED) "source_disabled" else "source_degraded",
        message = "source ${source.key} is ${reason.name.lowercase()}",
        details = buildJsonObject { put("sourceId", source.key) },
    )

/** Runs [block] and maps a [SourceUnavailableException] to the matching [ApiException]. */
suspend fun <T> withSourceErrors(block: suspend () -> T): T =
    try {
        block()
    } catch (error: SourceUnavailableException) {
        throw sourceUnavailable(error.source, error.reason)
    }

/** Cursor and login rules shared by `home.more`, `browse.page` and `artist.tracks`. */
object BrowseCursor {
    private const val PERSONAL = "login:"

    /** Continuation token as a wire cursor; personal feeds carry a `login:` prefix so `home.more` knows to sign the call. */
    fun wrap(
        token: String?,
        personal: Boolean,
    ): String? = token?.let { if (personal) PERSONAL + it else it }

    /** The token and whether the call needs the signed-in session. */
    fun unwrap(cursor: String): Pair<String, Boolean> =
        if (cursor.startsWith(PERSONAL)) cursor.removePrefix(PERSONAL) to true else cursor to false

    /**
     * Replicates `YouTubeRepository.browseUsesLogin` (internal there) minus the session flag:
     * library, moods and genres, and the VLSE / VLRDPN playlists need the signed-in session.
     */
    fun usesLogin(browseId: String?): Boolean =
        browseId?.startsWith("FEmusic_library") == true ||
            browseId?.startsWith("FEmusic_moods_and_genres") == true ||
            browseId == "VLSE" ||
            browseId == "VLRDPN"
}
