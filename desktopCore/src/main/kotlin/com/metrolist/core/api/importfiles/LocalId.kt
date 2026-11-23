package com.metrolist.core.api.importfiles

import java.security.MessageDigest

/** Ids of imported local files (api.md 1.11): `local_` plus the first 16 hex digits of the SHA-1 of the absolute path. */
object LocalId {
    const val PREFIX = "local_"
    private const val HEX_DIGITS = 16

    fun of(absolutePath: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(absolutePath.toByteArray(Charsets.UTF_8))
        return PREFIX + digest.joinToString("") { "%02x".format(it) }.take(HEX_DIGITS)
    }

    fun isLocal(id: String): Boolean = id.startsWith(PREFIX)
}
