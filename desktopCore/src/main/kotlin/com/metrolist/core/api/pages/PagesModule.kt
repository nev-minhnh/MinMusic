package com.metrolist.core.api.pages

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler

/** `album.get`, `artist.get/about/tracks`, `playlist.get`, `podcast.get/episodes`, `related.get`. */
object PagesModule : ApiModule {
    override val handlers: Map<String, Handler> = PagesApi.handlers
}
