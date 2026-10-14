package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.ModelSetupPhase

class DemoModelSetupServiceTest {
    @Test
    fun setupSimulatesProgressBeforeBecomingReady() = runTest {
        val service = DemoModelSetupService()
        val setup = backgroundScope.launch { service.prepare() }

        runCurrent()
        assertEquals(ModelSetupPhase.CHOOSING_MODEL, service.state.value.phase)

        advanceTimeBy(400)
        runCurrent()
        assertEquals(ModelSetupPhase.DOWNLOADING_VOICE, service.state.value.phase)
        assertTrue(service.state.value.bytesDownloaded > 0L)
        assertTrue(service.state.value.bytesTotal > service.state.value.bytesDownloaded)

        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(ModelSetupPhase.LOADING_VOICE, service.state.value.phase)

        advanceTimeBy(600)
        runCurrent()
        assertEquals(ModelSetupPhase.DOWNLOADING_LLM, service.state.value.phase)
        assertTrue(service.state.value.bytesDownloaded > 0L)

        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
        assertEquals(1f, service.state.value.progress)
        setup.cancel()
    }

    @Test
    fun interruptedSetupCanBePreparedAgain() = runTest {
        val service = DemoModelSetupService()

        val setup = backgroundScope.launch { service.prepare() }
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        setup.cancel()

        service.prepare()

        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
    }

    @Test
    fun repeatedPreparationRemainsReady() = runTest {
        val service = DemoModelSetupService()

        service.prepare()
        val readyState = service.state.value
        service.prepare()

        assertEquals(readyState, service.state.value)
    }
}
