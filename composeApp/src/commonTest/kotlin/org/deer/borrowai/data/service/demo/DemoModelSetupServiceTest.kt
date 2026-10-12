package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
}
