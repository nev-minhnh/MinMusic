package com.metrolist.core.api.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingRuleTest {
    @Test
    fun freshProfileWithoutDatabaseIsAFirstRun() {
        assertEquals(OnboardingFlags(initialized = true, complete = false), OnboardingRule.resolve(OnboardingFlags(false, false), dbExistedAtStart = false))
    }

    @Test
    fun upgradeFromV1WithADatabaseIsCompleted() {
        assertEquals(OnboardingFlags(initialized = true, complete = true), OnboardingRule.resolve(OnboardingFlags(false, false), dbExistedAtStart = true))
    }

    @Test
    fun initialisedFlagsAreKeptSoAHalfFinishedOnboardingIsNotSkipped() {
        // Second launch: the first launch created the database, but the user never finished onboarding.
        val stored = OnboardingFlags(initialized = true, complete = false)
        assertEquals(stored, OnboardingRule.resolve(stored, dbExistedAtStart = true))
        val done = OnboardingFlags(initialized = true, complete = true)
        assertEquals(done, OnboardingRule.resolve(done, dbExistedAtStart = false))
    }

    @Test
    fun databasePathIsUnderMetrolistInTheDataHome() {
        assertEquals(File("D:/data", "metrolist/metrolist.db"), OnboardingRule.databaseFile(File("D:/data")))
    }
}
