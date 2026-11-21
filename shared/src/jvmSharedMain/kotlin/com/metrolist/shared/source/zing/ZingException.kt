package com.metrolist.shared.source.zing

sealed class ZingException(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Zing refuses the content for the caller's country ("Nội dung này không tải được cho quốc gia của bạn!"). */
    class GeoBlocked(
        val code: Int,
        message: String?,
    ) : ZingException(message)

    class NotFound(
        val code: Int,
        message: String?,
    ) : ZingException(message)

    /** The request needs a logged-in session, or every audio tier of the song is reserved for VIP accounts. */
    class AuthRequired(
        val code: Int,
        message: String?,
    ) : ZingException(message)

    /** Signature or API key rejected even after the keys were extracted again. */
    class Signature(
        val code: Int,
        message: String?,
    ) : ZingException(message)

    class Network(
        message: String?,
        cause: Throwable? = null,
    ) : ZingException(message, cause)

    /** Any other non-zero `err` code. */
    class Api(
        val code: Int,
        message: String?,
    ) : ZingException(message)

    companion object {
        const val ERR_SIGNATURE = -104
        const val ERR_API_KEY = -116
        const val ERR_SESSION = -201
        const val ERR_FORBIDDEN = -403
        const val ERR_SONG_NOT_FOUND = -1023

        internal val sessionErrors = setOf(ERR_SESSION, ERR_FORBIDDEN)
        internal val signatureErrors = setOf(ERR_SIGNATURE, ERR_API_KEY)

        /** Maps a non-zero `err`/`msg` pair. Zing has no documented code for geo blocks, so the message decides. */
        fun fromApi(
            code: Int,
            message: String?,
        ): ZingException =
            when {
                message != null && GEO_MESSAGE_MARKER.containsMatchIn(message) -> GeoBlocked(code, message)
                code == ERR_SONG_NOT_FOUND -> NotFound(code, message)
                code in signatureErrors -> Signature(code, message)
                code == ERR_FORBIDDEN -> AuthRequired(code, message)
                else -> Api(code, message)
            }

        private val GEO_MESSAGE_MARKER = Regex("quốc gia", RegexOption.IGNORE_CASE)
    }
}
