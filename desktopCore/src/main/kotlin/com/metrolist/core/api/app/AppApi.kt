package com.metrolist.core.api.app

import com.metrolist.core.api.NavTarget
import com.metrolist.core.api.PlayerApi
import com.metrolist.core.api.toJson
import com.metrolist.core.server.ApiException
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.handler
import com.metrolist.core.server.requireStr
import com.metrolist.core.server.unsupported
import com.metrolist.desktop.utils.DesktopUrlHandler
import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.source.SourceId
import com.metrolist.shared.source.sourceOf
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.utils.parseYouTubePodcastId
import com.metrolist.shared.utils.parseYouTubeUrl
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What `app.openUrl` does with a link: navigate somewhere, or play a track. */
sealed interface UrlTarget {
    data class Nav(
        val route: String,
        val id: String,
    ) : UrlTarget

    data class Play(
        val id: String,
    ) : UrlTarget
}

/** api.md 3.1: `app.openUrl`, `app.copyLink`. */
object AppApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "app.openUrl" to handler { params, _ -> openUrl(params.requireStr("url")) },
            "app.copyLink" to handler { params, _ -> buildJsonObject { put("url", copyLink(params.requireStr("id"), params.requireStr("kind"))) } },
        )

    // --- app.openUrl ---------------------------------------------------------------------------------------------

    /**
     * Maps a parsed link to its target. A podcast id wins over a parsed YouTube URL (as in DesktopUrlHandler);
     * albums, playlists and artists navigate, a video plays, anything else is not handled (null).
     */
    fun navFor(
        parsed: YouTubeUrlParser.ParsedUrl?,
        podcastId: String? = null,
    ): UrlTarget? {
        if (podcastId != null) return UrlTarget.Nav("podcast", podcastId)
        return when (parsed) {
            is YouTubeUrlParser.ParsedUrl.Album -> UrlTarget.Nav("album", parsed.id)
            is YouTubeUrlParser.ParsedUrl.Playlist -> UrlTarget.Nav("playlist", parsed.id)
            is YouTubeUrlParser.ParsedUrl.Artist -> UrlTarget.Nav("artist", parsed.id)
            is YouTubeUrlParser.ParsedUrl.Video -> UrlTarget.Play(parsed.id)
            else -> null
        }
    }

    /**
     * Raw pasted text or a deep link to a target. Uses the same gate as the v1 desktop app (`canHandle`, `normalizeUrl`;
     * never `handleUrl`, which touches the UI), then the shared YouTube URL parsers. Null when the text is not ours.
     */
    fun resolveUrl(raw: String): UrlTarget? {
        if (!DesktopUrlHandler.canHandle(raw)) return null
        val normalized = DesktopUrlHandler.normalizeUrl(raw) ?: return null
        return navFor(parseYouTubeUrl(normalized), parseYouTubePodcastId(normalized))
    }

    private suspend fun openUrl(url: String): JsonElement {
        val target = resolveUrl(url)
        return when (target) {
            null -> {
                buildJsonObject { put("handled", false) }
            }

            is UrlTarget.Nav -> {
                buildJsonObject {
                    put("handled", true)
                    put("nav", NavTarget(target.route, buildJsonObject { put("id", target.id) }).toJson())
                }
            }

            is UrlTarget.Play -> {
                backendReady()
                PlayerApi.playIds(listOf(target.id))
                buildJsonObject { put("handled", true) }
            }
        }
    }

    // --- app.copyLink --------------------------------------------------------------------------------------------

    /**
     * Share URL of a YouTube item. [kind] is track (or song), album, artist, playlist or podcast. Other namespaces
     * (`sc:`, `zing:`, `nct:`, `local_`, user albums `lp:`) have no public link: `unsupported`.
     */
    fun copyLink(
        id: String,
        kind: String,
    ): String {
        if (sourceOf(id) != SourceId.YT || id.startsWith("lp:")) unsupported("no share link for '$id'")
        return when (kind) {
            "track", "song" -> ShareUrls.song(id)
            "album" -> ShareUrls.album(id)
            "artist" -> ShareUrls.artist(id)
            "playlist" -> ShareUrls.playlist(id)
            "podcast" -> ShareUrls.podcast(id)
            else -> throw ApiException("bad_request", "unknown kind '$kind'")
        }
    }
}
