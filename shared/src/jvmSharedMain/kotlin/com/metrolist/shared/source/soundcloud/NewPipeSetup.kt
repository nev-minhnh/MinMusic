package com.metrolist.shared.source.soundcloud

import com.metrolist.shared.network.sharedOkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

/**
 * Initializes NewPipeExtractor's process-wide state once.
 *
 * Localization always follows the current system locale; later calls only refresh it when the
 * locale changed, so a language switch without a process restart is honoured.
 */
object NewPipeSetup {
    private val lock = Any()

    @Volatile
    private var initialized = false

    fun ensureInitialized(downloaderFactory: () -> Downloader = { NewPipeOkHttpDownloader(sharedOkHttpClient) }) {
        val locale = Locale.getDefault()
        val localization = Localization.fromLocale(locale)
        val country = locale.country.takeIf { it.isNotEmpty() }?.let(::ContentCountry)
        synchronized(lock) {
            if (!initialized) {
                if (country == null) {
                    NewPipe.init(downloaderFactory(), localization)
                } else {
                    NewPipe.init(downloaderFactory(), localization, country)
                }
                initialized = true
            } else if (NewPipe.getPreferredLocalization() != localization) {
                NewPipe.setupLocalization(localization, country)
            }
        }
    }
}
