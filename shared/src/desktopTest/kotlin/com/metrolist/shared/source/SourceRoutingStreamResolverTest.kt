package com.metrolist.shared.source

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.youtube.playback.LocalAudioSource
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class SourceRoutingStreamResolverTest {
    private val youTube = RecordingResolver()
    private val soundCloud = FakeSource()
    private var soundCloudEnabled = false
    private var nowMs = 0L
    private var downloaded: PlaybackResource? = null
    private val resolver =
        SourceRoutingStreamResolver(
            delegate = youTube,
            registry = SourceRegistry(listOf(soundCloud), enabledSetting = { soundCloudEnabled }, nowMs = { nowMs }),
            localAudioSource = LocalAudioSource { _, _ -> downloaded },
        )

    @Test
    fun youTubeAndLocalFileIdsGoToTheYouTubeResolverUnchanged() =
        runBlocking<Unit> {
            assertEquals("youtube:dQw4w9WgXcQ", resolver.resolve("dQw4w9WgXcQ").url)
            assertEquals("youtube:local_42", resolver.refresh("local_42").url)
            assertEquals(
                "youtube:dQw4w9WgXcQ",
                resolver.resolveRemote("dQw4w9WgXcQ", ContentHints(), AudioQuality.AUTO, refresh = true).url,
            )
            assertEquals(listOf("dQw4w9WgXcQ", "local_42", "dQw4w9WgXcQ"), youTube.resolvedIds)
            assertEquals(0, soundCloud.streamCalls)
        }

    @Test
    fun disabledSourceFailsAsUnavailableWithoutCallingIt() =
        runBlocking<Unit> {
            val error = failure { resolver.resolve("sc:123") }

            assertEquals(StreamResolveException.Reason.UNAVAILABLE, error.reason)
            assertEquals(0, soundCloud.streamCalls)
            assertEquals(emptyList<String>(), youTube.resolvedIds)
            assertEquals(StreamResolveException.Reason.UNAVAILABLE, failure { resolver.resolve("zing:ZW6ABCDE") }.reason)
        }

    @Test
    fun enabledSourceStreamsAndDownloadedCopyWinsEvenWhenDisabled() =
        runBlocking<Unit> {
            soundCloudEnabled = true
            assertEquals("https://sc.example/123", resolver.resolve("sc:123").url)

            soundCloudEnabled = false
            downloaded = PlaybackResource("sc:123", "file:///music/123.m4a", emptyMap(), null, null)
            assertEquals("file:///music/123.m4a", resolver.resolve("sc:123").url)
            assertEquals(1, soundCloud.streamCalls)
            assertEquals(emptyList<String>(), youTube.resolvedIds)
        }

    @Test
    fun circuitBreakerOpensAfterFiveFailuresWithinTenMinutes() =
        runBlocking<Unit> {
            soundCloudEnabled = true
            soundCloud.failing = true
            repeat(5) {
                nowMs += 60_000L
                assertEquals(StreamResolveException.Reason.NETWORK, failure { resolver.resolve("sc:123") }.reason)
            }

            assertEquals(StreamResolveException.Reason.UNAVAILABLE, failure { resolver.resolve("sc:123") }.reason)
            assertEquals(5, soundCloud.streamCalls)

            soundCloudEnabled = false
            assertEquals(StreamResolveException.Reason.UNAVAILABLE, failure { resolver.resolve("sc:123") }.reason)
            soundCloudEnabled = true
            soundCloud.failing = false
            assertEquals("https://sc.example/123", resolver.resolve("sc:123").url)
            assertEquals(6, soundCloud.streamCalls)
        }

    @Test
    fun oldFailuresExpireFromTheBreakerWindow() =
        runBlocking<Unit> {
            soundCloudEnabled = true
            soundCloud.failing = true
            repeat(4) { failure { resolver.resolve("sc:123") } }
            nowMs += SourceRegistry.FAILURE_WINDOW_MS
            failure { resolver.resolve("sc:123") }

            assertEquals(StreamResolveException.Reason.NETWORK, failure { resolver.resolve("sc:123") }.reason)
            assertEquals(6, soundCloud.streamCalls)
        }

    private suspend fun failure(block: suspend () -> Unit): StreamResolveException =
        try {
            block()
            fail("Expected the stream to fail")
        } catch (error: StreamResolveException) {
            error
        }

    private class RecordingResolver : StreamResolver {
        val resolvedIds = mutableListOf<String>()

        override suspend fun resolve(
            videoId: String,
            hints: ContentHints,
            audioQuality: AudioQuality,
            sourceSong: SongItem?,
        ): PlaybackResource {
            resolvedIds += videoId
            return PlaybackResource(videoId, "youtube:$videoId", emptyMap(), null, null)
        }

        override suspend fun refresh(
            videoId: String,
            hints: ContentHints,
            audioQuality: AudioQuality,
            sourceSong: SongItem?,
        ): PlaybackResource = resolve(videoId, hints, audioQuality, sourceSong)
    }

    private class FakeSource : MusicSource {
        var failing = false
        var streamCalls = 0

        override val id = SourceId.SC
        override val capabilities = emptySet<SourceCapability>()

        override suspend fun stream(
            id: String,
            quality: SourceQuality,
        ): PlaybackResource {
            streamCalls++
            if (failing) error("SoundCloud is down")
            return PlaybackResource(id, "https://sc.example/" + id.removePrefix(SourceId.SC.prefix), emptyMap(), null, null)
        }

        override suspend fun search(
            query: String,
            filter: SearchFilter,
            page: Int,
        ): SourcePage<YTItem> = SourcePage(emptyList())

        override suspend fun track(id: String): SongItem = error("Not used")

        override suspend fun album(id: String): AlbumPage? = null

        override suspend fun playlist(id: String): SourcePlaylist? = null

        override suspend fun charts(): List<SourceChartSection> = emptyList()
    }
}
