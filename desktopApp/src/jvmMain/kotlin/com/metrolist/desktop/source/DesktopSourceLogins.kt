// stub: replaced by W2
package com.metrolist.desktop.source

import com.metrolist.shared.source.SourceId

/** Sign-in and sign-out for the sources with user accounts. W2 provides the real implementation. */
object DesktopSourceLogins {
    suspend fun loginZing(): Result<Unit> = Result.failure(UnsupportedOperationException("Zing sign-in is not available yet"))

    suspend fun loginNct(
        username: String,
        password: String,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("NCT sign-in is not available yet"))

    suspend fun logout(sourceId: SourceId) {
    }
}
