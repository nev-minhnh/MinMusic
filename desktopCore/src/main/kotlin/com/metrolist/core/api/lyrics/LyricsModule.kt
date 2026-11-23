package com.metrolist.core.api.lyrics

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.Topic

/** Lyrics: `lyrics.*` and the `lyrics` topic. */
object LyricsModule : ApiModule {
    override val handlers: Map<String, Handler> = LyricsApi.handlers
    override val topics: Map<String, Topic> = mapOf("lyrics" to LyricsApi.lyricsTopic)
}
