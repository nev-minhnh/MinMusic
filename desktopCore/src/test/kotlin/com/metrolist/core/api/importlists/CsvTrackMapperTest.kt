package com.metrolist.core.api.importlists

import com.metrolist.shared.playlistimport.PlaylistImportManager.CsvTrack
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import kotlinx.coroutines.CancellationException
import meow.nyx.spotlib.SpotifySessionExpiredException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CsvTrackMapperTest {
    @Test
    fun spotifyTrackKeepsAllFields() {
        val track = CsvTrackMapper.fromSpotify(SpotifyImportManager.ImportTrack("Song", "A, B", "Album", 215L))
        assertEquals(CsvTrack("Song", "A, B", null, album = "Album", durationSeconds = 215L), track)
        assertNull(track.videoId)
    }

    @Test
    fun spotifyTrackWithoutExtras() {
        val track = CsvTrackMapper.fromSpotify(SpotifyImportManager.ImportTrack("Song", ""))
        assertEquals("", track.album)
        assertNull(track.durationSeconds)
    }

    @Test
    fun labelNamesArtistAndTitle() {
        assertEquals("Artist - Title", CsvTrackMapper.label(CsvTrack(" Title ", " Artist ", null)))
        assertEquals("Title", CsvTrackMapper.label(CsvTrack("Title", "  ", null)))
    }

    @Test
    fun unresolvedLabelsPairDuplicatesOneToOne() {
        val a = CsvTrack("A", "X", null)
        val b = CsvTrack("B", "Y", null)
        val tracks = listOf(a, b, a, a)
        // one of the three A's matched and B matched: two A's are left over
        assertEquals(listOf("X - A", "X - A"), CsvTrackMapper.unresolvedLabels(tracks, listOf(a, b)))
        assertEquals(emptyList(), CsvTrackMapper.unresolvedLabels(tracks, tracks))
        assertEquals(listOf("X - A", "Y - B", "X - A", "X - A"), CsvTrackMapper.unresolvedLabels(tracks, emptyList()))
    }

    @Test
    fun providerErrorsMapToApiCodes() {
        assertEquals("not_signed_in", mapImportError(IllegalStateException("Sign in to Tidal first")).code)
        assertEquals("not_signed_in", mapImportError(SpotifySessionExpiredException("expired")).code)
        val network = mapImportError(java.io.IOException("x"))
        assertEquals("network", network.code)
        assertEquals("internal", mapImportError(RuntimeException("secret=abc")).code)
        assertFalse(mapImportError(RuntimeException("secret=abc")).message.orEmpty().contains("secret"))
    }

    @Test
    fun cancellationIsNotMapped() {
        assertFailsWith<CancellationException> { mapImportError(CancellationException("c")) }
    }
}
