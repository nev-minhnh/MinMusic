package com.metrolist.shared.source.zing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * Self-contained Zing MP3 client over [ZingApi]. Ids may be bare encodeIds or carry the "zing:" prefix.
 * Stream selection never uses a tier whose value is "VIP".
 */
class ZingClient(
    private val api: ZingApi,
    private val http: ZingHttp,
    private val session: ZingSession,
    private val userAgent: String = ZingApi.DEFAULT_USER_AGENT,
    private val vipProbeSongId: String = DEFAULT_VIP_PROBE_SONG_ID,
) {
    suspend fun search(
        query: String,
        type: ZingSearchType? = null,
        page: Int = 1,
    ): ZingSearchResult =
        io {
            require(page >= 1) { "page starts at 1" }
            if (type == null) {
                ZingMapper.multiSearchResult(api.get(ZingEndpoint.SEARCH_MULTI, mapOf("q" to query)).asObject())
            } else {
                val params =
                    mapOf("q" to query, "type" to type.param, "page" to page.toString(), "count" to SEARCH_PAGE_SIZE.toString())
                ZingMapper.searchResult(api.get(ZingEndpoint.SEARCH, params).asObject(), type, page, SEARCH_PAGE_SIZE)
            }
        }

    suspend fun song(id: String): ZingSong =
        io {
            ZingMapper.song(api.get(ZingEndpoint.SONG_INFO, mapOf("id" to zingId(id))).asObject())
                ?: throw ZingException.NotFound(0, "No song")
        }

    suspend fun stream(
        id: String,
        preferLossless: Boolean,
    ): ZingStream =
        io {
            val data = api.get(ZingEndpoint.SONG_STREAMING, mapOf("id" to zingId(id))).asObject()
            ZingMapper.selectStream(data, preferLossless)
        }

    suspend fun playlist(id: String): ZingPlaylist =
        io { ZingMapper.playlist(api.get(ZingEndpoint.PLAYLIST, mapOf("id" to zingId(id))).asObject()) }

    /** Albums are served by the playlist page endpoint. */
    suspend fun album(id: String): ZingPlaylist = playlist(id)

    suspend fun artist(alias: String): ZingArtist =
        io {
            require(alias.isNotBlank() && aliasPattern.matches(alias)) { "Invalid Zing artist alias" }
            ZingMapper.artist(api.get(ZingEndpoint.ARTIST, mapOf("alias" to alias)).asObject())
        }

    suspend fun charts(): ZingCharts = io { ZingMapper.charts(api.get(ZingEndpoint.CHART_HOME).asObject()) }

    suspend fun weekChart(chartId: String): ZingWeekChart =
        io {
            // week and year 0 select the latest published week.
            val params = mapOf("id" to zingId(chartId), "week" to "0", "year" to "0")
            ZingMapper.weekChart("", api.get(ZingEndpoint.WEEK_CHART, params).asObject())
        }

    suspend fun newReleases(): List<ZingSong> =
        io {
            ZingMapper.songs(api.get(ZingEndpoint.NEW_RELEASE_CHART).asObject())
        }

    suspend fun top100(): List<ZingSection> = io { ZingMapper.sections(api.get(ZingEndpoint.TOP_100)) }

    /** Synced LRC text, from `data.file` when Zing has one, otherwise rebuilt from the word timings; null without lyrics. */
    suspend fun lyrics(id: String): String? =
        io {
            val data =
                try {
                    api.get(ZingEndpoint.LYRIC, mapOf("id" to zingId(id))).asObject()
                } catch (_: ZingException.NotFound) {
                    return@io null
                }
            ZingMapper.lyricFileUrl(data)?.let { fetchLyricFile(it) } ?: ZingMapper.lrcFromSentences(data)
        }

    /**
     * Whether the session is logged in and, if so, its entitlement. VIP is detected by the "320" tier of a known song
     * being a URL instead of the "VIP" placeholder.
     */
    suspend fun account(): ZingAccount =
        io {
            val profile = api.get(ZingEndpoint.USER_PROFILE).asObject()
            val userId = ZingMapper.profileUserId(profile)
            if (userId == null || !session.hasCredentials()) {
                ZingAccount(loggedIn = false, userId = null, displayName = null, entitlement = ZingEntitlement.FREE)
            } else {
                ZingAccount(true, userId, ZingMapper.profileName(profile), probeEntitlement())
            }
        }

    private suspend fun probeEntitlement(): ZingEntitlement =
        try {
            val data = api.get(ZingEndpoint.SONG_STREAMING, mapOf("id" to vipProbeSongId)).asObject()
            val tier = ZingMapper.streams(data).firstOrNull { it.quality == ZingQuality.KBPS_320 }
            when {
                tier != null -> ZingEntitlement.VIP
                data[ZingQuality.KBPS_320.key] != null -> ZingEntitlement.FREE
                else -> ZingEntitlement.UNKNOWN
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ZingException) {
            ZingEntitlement.UNKNOWN
        }

    private suspend fun fetchLyricFile(url: String): String? {
        val response =
            try {
                http.get(url, mapOf("User-Agent" to userAgent))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        if (response.code !in 200..299) return null
        return response.body
            .removePrefix("﻿")
            .replace("\r\n", "\n")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: throw ZingException.Api(0, "Unexpected data")

    companion object {
        const val SEARCH_PAGE_SIZE = 18

        /** "Nơi Này Có Anh": its "320" tier is "VIP" for free accounts and a URL for VIP accounts (checked 2026-11-21). */
        const val DEFAULT_VIP_PROBE_SONG_ID = "d7qtCrRV89zn"

        private val idPattern = Regex("[A-Za-z0-9]{8,13}")
        private val aliasPattern = Regex("[A-Za-z0-9.\\-]+")

        fun zingId(id: String): String {
            val bare = id.removePrefix(ZING_ID_PREFIX)
            require(idPattern.matches(bare)) { "Invalid Zing id" }
            return bare
        }

        fun create(
            okHttpClient: OkHttpClient = OkHttpClient(),
            credentials: CredentialProvider? = null,
            userAgent: String = ZingApi.DEFAULT_USER_AGENT,
        ): ZingClient {
            val http = OkHttpZingHttp(okHttpClient)
            val session = ZingSession(http, credentials, userAgent)
            val keys = ZingKeyProvider(http, userAgent)
            return ZingClient(ZingApi(http, keys, session, userAgent), http, session, userAgent)
        }
    }
}
