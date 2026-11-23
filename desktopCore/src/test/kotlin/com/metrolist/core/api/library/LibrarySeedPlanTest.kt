package com.metrolist.core.api.library

import com.metrolist.core.seed.SeedPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibrarySeedPlanTest {
    private val json =
        """
        {
          "songs": [
            {"id":"yt00000001","title":"Mưa","artists":[{"id":"UC1","name":"Ca sĩ"}],"album":{"id":"MPREb_1","title":"Album 1"},"durationMs":200000,"thumbnailUrl":"https://i.ytimg.com/a.jpg","liked":true,"addedAt":1700000003000,"plays":3},
            {"id":"zing:ZW1","title":"Em","artists":[{"name":"No Id"},{"id":"UC1","name":"Ca sĩ"}],"album":{"id":"zing:alb","title":"Not in the album list"},"durationMs":0,"liked":false,"addedAt":1700000002000,"plays":0},
            {"id":"nct:n1","title":"Anh","artists":[{"id":"UC2","name":"Two"}],"durationMs":90500,"liked":true,"addedAt":1700000001000,"plays":1},
            {"id":"yt00000001","title":"duplicate id","durationMs":1}
          ],
          "albums": [{"id":"MPREb_1","title":"Album 1","artists":[{"name":"Ca sĩ"}],"year":2023,"thumbnailUrl":"https://i.ytimg.com/al.jpg"}],
          "artists": [{"id":"UC1","name":"Ca sĩ","thumbnailUrl":"https://i.ytimg.com/ar.jpg"},{"id":"UC9","name":"Alone"}],
          "userAlbums": [
            {"title":"First","songIds":["yt00000001","zing:ZW1","missing","yt00000001"],"pinned":true},
            {"title":"Second","songIds":[]}
          ],
          "history": [
            {"songId":"yt00000001","playedAt":1700000009000,"playTimeMs":150000},
            {"songId":"yt00000001","playedAt":1700000008000,"playTimeMs":10000},
            {"songId":"unknown","playedAt":1700000007000,"playTimeMs":5},
            {"songId":"nct:n1","playedAt":1700000006000}
          ],
          "futureKey": 1
        }
        """.trimIndent()

    private val plan = SeedPlan.of(SeedPlan.parse(json), nowMs = 1_800_000_000_000)

    @Test
    fun songsKeepTheirFieldsAndDropDuplicateIds() {
        assertEquals(listOf("yt00000001", "zing:ZW1", "nct:n1"), plan.songs.map { it.id })
        val first = plan.songs[0]
        assertEquals("Mưa", first.title)
        assertEquals(200, first.duration)
        assertEquals("MPREb_1", first.albumId)
        assertEquals("Album 1", first.albumName)
        assertTrue(first.liked)
        assertEquals(LibraryDto.utc(1_700_000_003_000), first.inLibrary)
        assertEquals(LibraryDto.utc(1_700_000_003_000), first.likedDate)
        assertEquals(3 * 60_000L, first.totalPlayTime)
        assertEquals("yt", first.source)
        assertEquals("zing", plan.songs[1].source)
        assertEquals("nct", plan.songs[2].source)
        assertEquals(-1, plan.songs[1].duration, "a missing duration stays unknown")
        assertEquals(90, plan.songs[2].duration)
        assertNull(plan.songs[1].likedDate)
    }

    @Test
    fun artistsAreDeduplicatedAndAnIdlessOneGetsAStableLocalId() {
        val ids = plan.artists.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue("UC1" in ids && "UC2" in ids && "UC9" in ids)
        val local = plan.artists.single { it.isLocal }
        assertEquals("No Id", local.name)
        assertTrue(local.id.startsWith("LAseed"))
        assertEquals(local.id, com.metrolist.core.seed.SeedPlan.artistId(com.metrolist.core.seed.SeedArtistRef(null, "No Id")))
        assertEquals("https://i.ytimg.com/ar.jpg", plan.artists.single { it.id == "UC1" }.thumbnailUrl)
        assertEquals(listOf(local.id, "UC1"), plan.songArtists.filter { it.songId == "zing:ZW1" }.sortedBy { it.position }.map { it.artistId })
    }

    @Test
    fun onlyListedAlbumsGetARowAndAMap() {
        assertEquals(listOf("MPREb_1"), plan.albums.map { it.id })
        val album = plan.albums.single()
        assertEquals(1, album.songCount)
        assertEquals(200, album.duration)
        assertEquals(2023, album.year)
        assertEquals(listOf("yt00000001" to "MPREb_1"), plan.songAlbums.map { it.songId to it.albumId })
        assertEquals(1, plan.albumArtists.size)
        assertEquals("UC1", plan.albumArtists.single().artistId, "an album artist without an id is the artist of that name")
        // a song may still name an album that has no row of its own
        assertEquals("zing:alb", plan.songs[1].albumId)
    }

    @Test
    fun userAlbumsKeepKnownSongsOnceInOrderAndRememberPins() {
        assertEquals(listOf("First", "Second"), plan.userAlbums.map { it.entity.name })
        val first = plan.userAlbums[0]
        assertEquals(listOf("yt00000001", "zing:ZW1"), first.songIds)
        assertTrue(first.pinned)
        assertTrue(first.entity.isUserAlbum && first.entity.isLocal)
        assertEquals("https://i.ytimg.com/a.jpg", first.entity.thumbnailUrl)
        assertTrue(plan.userAlbums[1].songIds.isEmpty())
        assertTrue(!plan.userAlbums[1].pinned)
        // the first album is the newest, so it comes first in the createdAt DESC list
        assertTrue(first.entity.createdAt!! > plan.userAlbums[1].entity.createdAt!!)
    }

    @Test
    fun historySkipsUnknownSongsAndSnapshotsWhatWasPlayed() {
        assertEquals(3, plan.history.size)
        val play = plan.history[0]
        assertEquals("yt00000001", play.event.songId)
        assertEquals(LibraryDto.utc(1_700_000_009_000), play.event.timestamp)
        assertEquals(150_000L, play.event.playTime)
        assertTrue(play.event.playTimeKnown)
        assertEquals("Mưa", play.snapshot.title)
        assertEquals("Ca sĩ", play.snapshot.artists)
        assertEquals("Album 1", play.snapshot.album)
        assertEquals("MPREb_1", play.snapshot.albumId)
        assertEquals(200L, play.snapshot.duration)
        assertEquals("[\"Ca sĩ\"]", play.snapshot.artistNamesJson)
        assertEquals(0L, plan.history[2].event.playTime)
    }

    @Test
    fun likesAreNewestFirst() {
        assertEquals(listOf("yt00000001", "nct:n1"), plan.likes.map { it.id })
        assertEquals(200L, plan.likes[0].duration)
    }

    @Test
    fun realisticSeedSizes() {
        val songs = (1..5000).joinToString(",") { """{"id":"s$it","title":"t$it","artists":[{"id":"UC${it % 50}","name":"a${it % 50}"}],"durationMs":1000,"addedAt":$it}""" }
        val history = (1..2000).joinToString(",") { """{"songId":"s$it","playedAt":$it,"playTimeMs":1}""" }
        val big = SeedPlan.of(SeedPlan.parse("""{"songs":[$songs],"history":[$history]}"""), 1)
        assertEquals(5000, big.songs.size)
        assertEquals(50, big.artists.size)
        assertEquals(5000, big.songArtists.size)
        assertEquals(2000, big.history.size)
    }
}
