package com.metrolist.shared.source

import com.metrolist.shared.settings.Settings

fun Settings.isSourceEnabled(id: SourceId): Boolean =
    when (id) {
        SourceId.YT, SourceId.LOCAL -> true
        SourceId.SC -> soundCloudSourceEnabled
        SourceId.ZING -> zingSourceEnabled
        SourceId.NCT -> nctSourceEnabled
    }
