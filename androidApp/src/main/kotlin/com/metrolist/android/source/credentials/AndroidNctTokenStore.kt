package com.metrolist.android.source.credentials

import android.content.Context
import com.metrolist.shared.source.nct.NctTokenStore

// placeholder: replaced by UI branch
class AndroidNctTokenStore(
    @Suppress("unused") private val context: Context,
) : NctTokenStore {
    override suspend fun load(): String? = null

    override suspend fun save(token: String?) {}
}
