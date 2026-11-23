package com.metrolist.core.api.importlists

import com.metrolist.core.shell.WebLoginCollect
import com.metrolist.core.shell.WebLoginConfig
import com.metrolist.core.shell.WebLoginDone
import com.metrolist.shared.playlistimport.AmazonMusicPlaylistImporter
import java.net.URI

/**
 * The `shell.webLogin` configs of the five import providers (api.md section 5, architecture 5.1), taken from the v1 login
 * dialogs in `desktopApp/.../spotifyimport/*LoginDialog.kt`. The done cookie is the one the v1 extractor needs:
 * spotify `sp_dc`, tidal `userId` (and `sid`, collected), deezer `arl`, apple `media-user-token`, amazon a session token.
 * No user agent is set (the shell's WebView2 default), as for YouTube.
 *
 * Integration: M3-2 owns `WebLoginConfigs.kt` with a `// <M4-4a>` block; these functions may move there unchanged.
 */
object ImportLoginConfigs {
    const val DEFAULT_AMAZON_HOST = "music.amazon.com"

    private const val WINDOW_W = 560
    private const val WINDOW_H = 760

    fun forProvider(
        provider: ImportProviderId,
        amazonHost: String = DEFAULT_AMAZON_HOST,
    ): WebLoginConfig =
        when (provider) {
            ImportProviderId.SPOTIFY -> spotify()
            ImportProviderId.TIDAL -> tidal()
            ImportProviderId.DEEZER -> deezer()
            ImportProviderId.APPLE -> apple()
            ImportProviderId.AMAZON -> amazon(amazonHost)
        }

    fun spotify(): WebLoginConfig =
        WebLoginConfig(
            url = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F",
            allowedHosts =
                listOf(
                    "accounts.spotify.com", "open.spotify.com", "spotify.com", "*.spotify.com", "*.scdn.co",
                    "accounts.google.com", "*.google.com", "*.facebook.com", "appleid.apple.com", "*.apple.com",
                ),
            title = "Spotify",
            windowW = WINDOW_W,
            windowH = WINDOW_H,
            done = WebLoginDone(cookieNames = listOf("sp_dc"), cookieUrls = listOf("https://open.spotify.com")),
            collect = WebLoginCollect(cookieUrls = listOf("https://open.spotify.com", "https://spotify.com")),
        )

    /** `userId` only appears once signed in; `sid` can exist before, so it is collected but does not end the window. */
    fun tidal(): WebLoginConfig =
        WebLoginConfig(
            url = "https://listen.tidal.com/login",
            allowedHosts =
                listOf(
                    "listen.tidal.com", "tidal.com", "*.tidal.com", "login.tidal.com", "*.tidalhifi.com",
                    "accounts.google.com", "*.google.com", "*.facebook.com", "appleid.apple.com", "*.apple.com",
                ),
            title = "Tidal",
            windowW = WINDOW_W,
            windowH = WINDOW_H,
            done = WebLoginDone(cookieNames = listOf("userId"), cookieUrls = listOf("https://listen.tidal.com")),
            collect = WebLoginCollect(cookieUrls = listOf("https://listen.tidal.com", "https://tidal.com")),
        )

    fun deezer(): WebLoginConfig =
        WebLoginConfig(
            url = "https://www.deezer.com/",
            allowedHosts =
                listOf(
                    "www.deezer.com", "deezer.com", "*.deezer.com", "connect.deezer.com",
                    "accounts.google.com", "*.google.com", "*.facebook.com", "appleid.apple.com", "*.apple.com",
                ),
            title = "Deezer",
            windowW = WINDOW_W,
            windowH = WINDOW_H,
            done = WebLoginDone(cookieNames = listOf("arl"), cookieUrls = listOf("https://www.deezer.com")),
            collect = WebLoginCollect(cookieUrls = listOf("https://www.deezer.com", "https://deezer.com")),
        )

    fun apple(): WebLoginConfig =
        WebLoginConfig(
            url = "https://music.apple.com",
            allowedHosts = listOf("music.apple.com", "*.apple.com", "*.icloud.com", "*.mzstatic.com"),
            title = "Apple Music",
            windowW = WINDOW_W,
            windowH = WINDOW_H,
            done = WebLoginDone(cookieNames = listOf("media-user-token"), cookieUrls = listOf("https://music.apple.com")),
            collect = WebLoginCollect(cookieUrls = listOf("https://music.apple.com")),
        )

    /**
     * [host] is one of `AmazonMusicPlaylistImporter.marketplaceHosts`. `session-id` is set before sign-in, so the window
     * waits for the auth token cookies instead; `extractSessionCookie` accepts the whole header afterwards.
     */
    fun amazon(host: String): WebLoginConfig {
        require(host in AmazonMusicPlaylistImporter.marketplaceHosts) { "Unsupported Amazon Music marketplace" }
        val base = "https://$host"
        return WebLoginConfig(
            url = base,
            allowedHosts = (AmazonMusicPlaylistImporter.marketplaceHosts + amazonAccountHosts).toList(),
            title = "Amazon Music",
            windowW = WINDOW_W,
            windowH = WINDOW_H,
            done = WebLoginDone(cookieNames = AMAZON_DONE_COOKIES, cookieUrls = listOf(base)),
            collect = WebLoginCollect(cookieUrls = listOf(base)),
        )
    }

    private val AMAZON_DONE_COOKIES = listOf("at-main", "at-acbuk", "at-acbde", "at-acbfr", "at-acbit", "at-acbes", "at-acbjp")

    /** Amazon signs in on the retail domains, then returns to the music host. */
    private val amazonAccountHosts =
        listOf(
            "www.amazon.com", "www.amazon.co.uk", "www.amazon.de", "www.amazon.fr", "www.amazon.it", "www.amazon.es", "www.amazon.co.jp",
            "*.amazon.com", "*.amazon.co.uk", "*.amazon.de", "*.amazon.fr", "*.amazon.it", "*.amazon.es", "*.amazon.co.jp",
        )

    /**
     * The marketplace host the user ended on (from `finalUrl`), or [requested] when the final URL is missing or is not
     * a known marketplace.
     */
    fun amazonHost(
        finalUrl: String?,
        requested: String = DEFAULT_AMAZON_HOST,
    ): String {
        val host = runCatching { URI(finalUrl.orEmpty()).host }.getOrNull()?.lowercase()
        return host?.takeIf { it in AmazonMusicPlaylistImporter.marketplaceHosts } ?: requested
    }
}
