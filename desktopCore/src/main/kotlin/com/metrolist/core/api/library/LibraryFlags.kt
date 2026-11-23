package com.metrolist.core.api.library

import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.resolveArtistSubscribed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The saved and subscribed flags of the album and artist pages (`album.get` `saved`, `artist.get` `subscribed`). */
object LibraryFlags {
    suspend fun albumSaved(id: String): Boolean = withContext(Dispatchers.IO) { libraryDao.album(id).first()?.album?.bookmarkedAt != null }

    /** With a signed-in session YouTube's answer ([browseSubscribed]) wins, otherwise the local bookmark does. */
    suspend fun artistSubscribed(
        id: String,
        browseSubscribed: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            // MusicDatabase.resolveArtistSubscribed(repository, artistId, browseSubscribed): Boolean
            libraryDatabase.resolveArtistSubscribed(GlobalYouTubeRepository.instance, id, browseSubscribed)
        }
}
