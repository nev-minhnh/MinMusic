package com.metrolist.shared.source.nct

import java.security.MessageDigest

/**
 * NhacCuaTui client over the graph.nct.vn Android-app API (contract as used by carlylekatto/nct.js).
 *
 * Ids are `nct:<key>`; bare keys are accepted too. Free sessions only receive streams that are not VIP-only.
 * The session token comes from [tokenStore]; this class never stores or logs passwords or tokens.
 */
class NctClient internal constructor(
    private val tokenStore: NctTokenStore,
    private val api: NctApi,
) {
    constructor(tokenStore: NctTokenStore) : this(tokenStore, NctApi())

    suspend fun search(
        query: String,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): NctSearchPage {
        val envelope =
            api.get(
                NctApi.SEARCH_SONG,
                mapOf(
                    "keyword" to query,
                    "pageindex" to page.coerceAtLeast(1).toString(),
                    "pagesize" to pageSize.toString(),
                    "correct" to "true",
                ),
                tokenStore.load(),
            )
        return NctSearchPage(
            songs = NctParser.searchSongs(envelope),
            page = page,
            total = envelope.total,
            hasMore = envelope.hasMore,
        )
    }

    suspend fun hotKeywords(): List<String> = NctParser.hotKeywords(api.get(NctApi.HOT_KEYWORDS).data)

    suspend fun song(id: String): NctSong {
        val envelope = api.get(NctApi.SONG_DETAIL + keyOf(id), token = tokenStore.load())
        return NctParser.song(envelope.data) ?: throw NctException.NotFound(null)
    }

    /** Returns the best stream the session may play; throws [NctException.VipRequired] when only VIP streams exist. */
    suspend fun stream(id: String): NctStream {
        val token = tokenStore.load()
        val vip = token != null && account(token).entitlement == NctEntitlement.VIP
        val envelope = api.get(NctApi.SONG_DETAIL + keyOf(id), token = token)
        val song = NctParser.song(envelope.data) ?: throw NctException.NotFound(null)
        return NctParser.selectStream(song.streams, vip)
    }

    suspend fun lyrics(id: String): NctLyrics? {
        val envelope =
            try {
                api.get(NctApi.SONG_LYRIC, mapOf("songKey" to keyOf(id)), tokenStore.load())
            } catch (_: NctException.NotFound) {
                return null
            }
        val detail = NctParser.lyricDetail(envelope.data) ?: return null
        val synced =
            detail.timedLyricUrl?.let { url ->
                try {
                    NctParser.decodeTimedLyric(api.getLyricFile(url), detail.decryptKey)
                } catch (_: NctException) {
                    null
                }
            }
        if (synced == null && detail.plain == null) return null
        return NctLyrics(synced = synced, plain = detail.plain, aiGenerated = detail.aiGenerated)
    }

    suspend fun playlist(
        id: String,
        page: Int = 1,
        pageSize: Int = PLAYLIST_PAGE_SIZE,
    ): NctPlaylist {
        val key = keyOf(id)
        val envelope =
            api.get(
                NctApi.PLAYLIST_DETAIL + key,
                mapOf("key" to key, "pn" to page.coerceAtLeast(1).toString(), "rn" to pageSize.toString()),
                tokenStore.load(),
            )
        return NctParser.playlist(envelope.data, envelope.hasMore) ?: throw NctException.NotFound(null)
    }

    suspend fun charts(): List<NctChart> {
        val data = api.get(NctApi.CHARTS, token = tokenStore.load()).data
        return NctParser.charts(data)
    }

    suspend fun chart(id: String): NctPlaylist {
        val envelope = api.get("${NctApi.CHARTS}/${keyOf(id)}", token = tokenStore.load())
        return NctParser.chartPlaylist(envelope.data) ?: throw NctException.NotFound(null)
    }

    /** Reports the stored session; a token the server rejects is cleared from [tokenStore]. */
    suspend fun account(): NctAccount {
        val token = tokenStore.load() ?: return NctAccount.LoggedOut
        return account(token)
    }

    private suspend fun account(token: String): NctAccount =
        try {
            NctParser.account(api.get(NctApi.ACCOUNT_INFO, token = token).data)
        } catch (_: NctException.AuthFailed) {
            tokenStore.save(null)
            NctAccount.LoggedOut
        }

    /**
     * Logs in with the account password. The API requires the MD5 hex digest of the password; it is sent only in
     * this HTTPS request and never stored. The returned token is saved through [tokenStore].
     */
    suspend fun loginWithPassword(
        username: String,
        password: String,
    ): NctToken {
        val envelope =
            try {
                api.postForm(
                    NctApi.LOGIN,
                    mapOf(
                        "phone" to "",
                        "countryCode" to "",
                        "account" to username,
                        "pwd" to md5Hex(password),
                        "source" to "other",
                    ),
                )
            } catch (e: NctException.Unexpected) {
                throw NctException.AuthFailed(e.code)
            } catch (e: NctException.NotFound) {
                throw NctException.AuthFailed(e.code)
            }
        val token = NctParser.login(envelope.data)
        tokenStore.save(token.value)
        return token
    }

    suspend fun logout() {
        tokenStore.save(null)
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
        private const val PLAYLIST_PAGE_SIZE = 100

        fun keyOf(id: String): String {
            val key = id.removePrefix(NCT_ID_PREFIX)
            if (!NctApi.isValidKey(key)) throw NctException.NotFound(null)
            return key
        }

        internal fun md5Hex(value: String): String =
            MessageDigest
                .getInstance("MD5")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
