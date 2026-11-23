package com.metrolist.core.api.onboarding

import com.metrolist.core.api.ApiModule
import com.metrolist.core.server.Handler
import com.metrolist.core.server.backendReady
import com.metrolist.core.server.emptyResult
import com.metrolist.core.server.handler
import com.metrolist.shared.settings.Settings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** The two persisted onboarding flags (Settings.desktopOnboardingInitialized / desktopOnboardingComplete). */
data class OnboardingFlags(
    val initialized: Boolean,
    val complete: Boolean,
)

/** Pure rules of onboarding (docs/api.md 3.15, v1 Main.kt:899-902). */
object OnboardingRule {
    /**
     * Once per profile: the first launch with the flags not yet initialised counts as a first run only when no database
     * existed before this launch (an upgrade from v1 has one, so it is "completed" without showing onboarding). Later
     * launches keep the stored flags, so closing the app mid-onboarding does not skip it next time.
     */
    fun resolve(
        flags: OnboardingFlags,
        dbExistedAtStart: Boolean,
    ): OnboardingFlags = if (flags.initialized) flags else OnboardingFlags(initialized = true, complete = dbExistedAtStart)

    /** `<data>/metrolist/metrolist.db` (v1 Main.kt:901). */
    fun databaseFile(dataHome: File): File = File(dataHome, "metrolist/metrolist.db")
}

/** `onboarding.state` -> `{completed}` (matches protocol/src/methods.ts), `onboarding.complete`. */
object OnboardingModule : ApiModule {
    /** Called by CoreBackend.init (before the pending restore and the database open) with the captured `dbExistedAtStart`. */
    fun initialize(
        settings: Settings,
        dbExistedAtStart: Boolean,
    ) {
        val before = OnboardingFlags(settings.desktopOnboardingInitialized, settings.desktopOnboardingComplete)
        val after = OnboardingRule.resolve(before, dbExistedAtStart)
        if (after == before) return
        settings.desktopOnboardingComplete = after.complete
        settings.desktopOnboardingInitialized = after.initialized
    }

    override val handlers: Map<String, Handler> =
        mapOf(
            "onboarding.state" to
                handler { _, _ ->
                    backendReady()
                    buildJsonObject { put("completed", Settings.shared.desktopOnboardingComplete) }
                },
            "onboarding.complete" to
                handler { _, _ ->
                    backendReady()
                    Settings.shared.desktopOnboardingComplete = true
                    Settings.shared.desktopOnboardingInitialized = true
                    emptyResult()
                },
        )
}
