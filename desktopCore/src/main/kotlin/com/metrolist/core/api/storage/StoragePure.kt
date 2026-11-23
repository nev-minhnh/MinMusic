package com.metrolist.core.api.storage

import java.io.File
import java.nio.file.Files

/** The caches `storage.clear` accepts (api.md 3.9); `downloads` is not one of them. */
enum class CacheKind(
    val wire: String,
) {
    SONG("songCache"),
    IMAGE("imageCache"),
    VIDEO("videoCache"),
    ANIMATED("animatedArt"),
    ;

    companion object {
        fun parse(value: String?): CacheKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Sizes of directory trees, for the caches that are plain folders. */
object DirSize {
    /** Total bytes of the regular files under [dir]; 0 when it does not exist. Unreadable entries count as 0. */
    fun bytes(dir: File): Long {
        if (!dir.isDirectory) return 0
        return runCatching {
            Files.walk(dir.toPath()).use { stream ->
                stream.mapToLong { p -> runCatching { if (Files.isRegularFile(p)) Files.size(p) else 0L }.getOrDefault(0L) }.sum()
            }
        }.getOrDefault(0L)
    }

    /** Deletes everything inside [dir] and keeps [dir] itself. Returns false when something could not be deleted. */
    fun clearContents(dir: File): Boolean {
        if (!dir.isDirectory) return true
        var ok = true
        dir.listFiles()?.forEach { child -> if (!child.deleteRecursively()) ok = false }
        return ok
    }
}

/** `limits` of `storage.usage`: megabyte settings to bytes; a value <= 0 means "no limit" and is omitted. */
object StorageLimits {
    fun toBytes(mb: Long): Long? = mb.takeIf { it > 0 }?.times(1024L * 1024L)
}
