package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.ModelSetupPhase

@OptIn(ExperimentalCoroutinesApi::class)
class DemoModelSetupServiceTest {
    @Test
    fun setupRetainsTheExistingFiveSecondProgressDuration() = runTest {
        val service = DemoModelSetupService()
        val setup = backgroundScope.launch { service.prepare() }

        runCurrent()
        assertNotEquals(ModelSetupPhase.READY, service.state.value.phase)
        advanceTimeBy(4_999)
        runCurrent()
        assertNotEquals(ModelSetupPhase.READY, service.state.value.phase)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
        setup.cancel()
    }

    @Test
    fun cancelledSetupResumesFromItsCurrentProgress() = runTest {
        val service = DemoModelSetupService(stepDelayMillis = 100)
        val setup = backgroundScope.launch { service.prepare() }

        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        setup.cancel()
        runCurrent()
        val progressBeforeResume = service.state.value.progress
        assertTrue(progressBeforeResume > 0f)

        val resumedSetup = backgroundScope.launch { service.prepare() }
        runCurrent()
        assertEquals(progressBeforeResume, service.state.value.progress)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(service.state.value.progress > progressBeforeResume)

        advanceTimeBy(4_900)
        runCurrent()
        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
        resumedSetup.cancel()
    }
}
