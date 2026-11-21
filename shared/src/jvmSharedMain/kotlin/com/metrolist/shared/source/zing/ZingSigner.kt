package com.metrolist.shared.source.zing

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Request signature of the zingmp3.vn web API, as implemented by ZingMP3Explode, NodeLink and api-zingmp3:
 * hex(HMAC-SHA512(secret, path + hex(SHA256(concatenated "k=v" pieces sorted by key)))).
 * Only [signedKeys] take part and empty values are skipped; other query parameters are sent unsigned.
 */
object ZingSigner {
    val signedKeys: Set<String> = setOf("ctime", "id", "type", "page", "count", "version")

    fun sign(
        path: String,
        params: Map<String, String>,
        secret: String,
    ): String {
        val canonical =
            params
                .filter { (key, value) -> key in signedKeys && value.isNotEmpty() }
                .toSortedMap()
                .entries
                .joinToString("") { (key, value) -> "$key=$value" }
        val paramsHash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).toHex()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal((path + paramsHash).toByteArray(Charsets.UTF_8)).toHex()
    }

    private const val HMAC_ALGORITHM = "HmacSHA512"
}

private fun ByteArray.toHex(): String =
    buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

private const val HEX_DIGITS = "0123456789abcdef"
