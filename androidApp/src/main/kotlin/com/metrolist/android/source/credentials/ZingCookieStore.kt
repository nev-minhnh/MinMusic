package com.metrolist.android.source.credentials

import android.content.Context
import com.metrolist.shared.source.zing.CredentialProvider

// placeholder: replaced by UI branch
class ZingCookieStore(
    @Suppress("unused") private val context: Context,
) : CredentialProvider {
    override suspend fun cookieHeader(): String? = null
}
