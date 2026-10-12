package org.deer.borrowai.data.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.demo.DemoMediaSessionService
import org.deer.borrowai.data.service.demo.DemoModelSetupService
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService

class AppServicesTest {
    @Test
    fun startupFlagSelectsDemoOnlyForDemo() {
        assertEquals(AppMode.DEMO, AppMode.fromStartupFlag(" demo "))
        assertEquals(AppMode.REAL, AppMode.fromStartupFlag(null))
        assertEquals(AppMode.REAL, AppMode.fromStartupFlag("invalid"))
        assertEquals(AppMode.REAL, AppMode.fromStartupFlag("real"))

        val services = AppServices.createFromStartupFlag("demo", EpochClock { 1_800_000_000_000L })
        assertEquals(AppMode.DEMO, services.mode)
        assertNotNull(services.modelSetup as? DemoModelSetupService)
        assertNotNull(services.memories as? InMemoryDemoMemoryService)
        assertNotNull(services.mediaSession as? DemoMediaSessionService)
    }

    @Test
    fun realModeFailsInsteadOfFallingBackToDemo() {
        assertFailsWith<RealServicesUnavailableException> {
            AppServices.createFromStartupFlag("real", EpochClock { 1_800_000_000_000L })
        }
        assertFailsWith<RealServicesUnavailableException> {
            AppServices.createFromStartupFlag(null, EpochClock { 1_800_000_000_000L })
        }
    }
}
