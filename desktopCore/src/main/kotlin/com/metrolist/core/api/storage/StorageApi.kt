package com.metrolist.core.api.storage

import com.metrolist.core.api.download.DownloadApi
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.badRequest
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.core.server.str
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.DesktopAnimatedArtworkCache
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.core.context.GlobalContext
import java.io.File

/** api.md 3.9 `storage.usage` and `storage.clear`. */
object StorageApi {
    val handlers: Map<String, Handler> =
        mapOf(
            "storage.usage" to handler { _, _ -> usage() },
            "storage.clear" to handler { p, _ -> clear(p.str("what")) },
        )

    /**
     * The image cache directory the v1 desktop image loader uses (`cache\metrolist\image_cache`). The core never opens
     * the Coil loader, so its size is the directory size and clearing it empties the directory.
     */
    private fun imageCacheDir(): File = File(XdgUtils.getXdgCacheHome(), "metrolist/image_cache")

    private fun videoResolver(): MusicVideoResolver? = GlobalContext.getOrNull()?.getOrNull<MusicVideoResolver>()

    private suspend fun usage(): JsonElement {
        backendReady()
        return withContext(Dispatchers.IO) {
            buildJsonObject {
                // SongCacheManager.getCacheSizeBytes(): Long
                put("songCache", SongCacheManager.getCacheSizeBytes())
                put("imageCache", DirSize.bytes(imageCacheDir()))
                // MusicVideoResolver.getCacheSizeBytes(): Long (the media-link cache lives in a settings JSON, not a folder)
                put("videoCache", videoResolver()?.getCacheSizeBytes() ?: 0L)
                // DesktopAnimatedArtworkCache.get().sizeBytes(): Long
                put("animatedArt", DesktopAnimatedArtworkCache.get().sizeBytes())
                put("downloads", DownloadApi.downloadsBytes())
                putJsonObject("limits") {
                    // getMaxCacheSizeMB(): Long
                    StorageLimits.toBytes(SongCacheManager.getMaxCacheSizeMB())?.let { put("songCache", it) }
                    StorageLimits.toBytes(Settings.shared.maxImageCacheSize.toLong())?.let { put("imageCache", it) }
                    StorageLimits.toBytes(Settings.shared.animatedArtworkCacheSize.toLong())?.let { put("animatedArt", it) }
                }
            }
        }
    }

    private suspend fun clear(what: String?): JsonObject {
        backendReady()
        val kind = CacheKind.parse(what) ?: badRequest("what must be one of songCache, imageCache, videoCache, animatedArt")
        when (kind) {
            // clearCache()
            CacheKind.SONG -> withContext(Dispatchers.IO) { SongCacheManager.clearCache() }
            CacheKind.IMAGE -> withContext(Dispatchers.IO) { DirSize.clearContents(imageCacheDir()) }
            // suspend clearCache()
            CacheKind.VIDEO -> videoResolver()?.clearCache()
            // clear()
            CacheKind.ANIMATED -> withContext(Dispatchers.IO) { DesktopAnimatedArtworkCache.get().clear() }
        }
        return emptyResult()
    }
}
