package org.deer.borrowai.data.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.demo.DemoMediaSessionService
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService
import org.deer.borrowai.data.service.model.DownloadingModelSetupService

class AppServicesTest {
    @Test
    fun demoVariantBuildsEveryServiceAsDemo() {
        val services = AppServices.createFromServiceVariant(
            "demo",
            EpochClock { 1_800_000_000_000L },
            TestDeviceEnvironment(),
        )

        assertEquals(ServiceVariants.DEMO, services.variant)
        assertTrue(services.variant.hasDemoServices)
        assertNotNull(services.modelSetup as? DownloadingModelSetupService)
        assertNotNull(services.memories as? InMemoryDemoMemoryService)
        assertNotNull(services.mediaSession as? DemoMediaSessionService)
        services.close()
    }

    @Test
    fun realModelSetupVariantUsesDownloadingServiceAndKeepsOtherServicesDemo() {
        val services = AppServices.createFromServiceVariant(
            "real-model-setup",
            EpochClock { 1_800_000_000_000L },
            TestDeviceEnvironment(),
        )

        assertNotNull(services.modelSetup as? DownloadingModelSetupService)
        assertNotNull(services.memories as? InMemoryDemoMemoryService)
        assertNotNull(services.mediaSession as? DemoMediaSessionService)
        services.close()
    }

    @Test
    fun unknownOrMissingVariantFailsFast() {
        assertFailsWith<UnknownServiceVariantException> {
            AppServices.createFromServiceVariant("real", EpochClock { 1_800_000_000_000L }, TestDeviceEnvironment())
        }
        assertFailsWith<UnknownServiceVariantException> {
            AppServices.createFromServiceVariant("", EpochClock { 1_800_000_000_000L }, TestDeviceEnvironment())
        }
        assertFailsWith<UnknownServiceVariantException> {
            AppServices.createFromServiceVariant(null, EpochClock { 1_800_000_000_000L }, TestDeviceEnvironment())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closingServiceGraphCancelsMediaSessionWork() = runTest {
        val services = AppServices.createFromServiceVariant(
            serviceVariantId = "demo",
            epochClock = EpochClock { 1_800_000_000_000L },
            device = TestDeviceEnvironment(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        services.mediaSession.startRecording()
        advanceTimeBy(45)
        runCurrent()
        assertEquals(
            "T",
            assertNotNull(services.mediaSession.state.value as? MediaSessionState.Recording)
                .transcription,
        )

        services.close()
        assertEquals(MediaSessionState.Idle, services.mediaSession.state.value)
        val stateAfterClose = services.mediaSession.state.value
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(stateAfterClose, services.mediaSession.state.value)
        assertFailsWith<IllegalStateException> {
            services.mediaSession.startRecording()
        }
        services.close()
    }
}
