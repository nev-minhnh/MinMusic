package com.metrolist.shared.source.nct

/** Messages never include request bodies, headers, tokens or passwords. */
sealed class NctException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class AuthFailed(
        val code: Int?,
    ) : NctException("NhacCuaTui rejected the session (code $code)")

    class VipRequired : NctException("NhacCuaTui stream requires a VIP account")

    class NotFound(
        val code: Int?,
    ) : NctException("NhacCuaTui item does not exist (code $code)")

    class Blocked(
        val httpCode: Int?,
    ) : NctException("NhacCuaTui refused the client (HTTP $httpCode)")

    class Network(
        cause: Throwable?,
        val httpCode: Int? = null,
    ) : NctException("NhacCuaTui request failed (HTTP $httpCode)", cause)

    class Unexpected(
        val code: Int?,
    ) : NctException("NhacCuaTui returned an unexpected response (code $code)")
}
