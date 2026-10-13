package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.deer.borrowai.data.service.ModelSetupPhase

class DemoModelSetupServiceTest {
    @Test
    fun setupIsImmediatelyReady() = runTest {
        val service = DemoModelSetupService()

        service.prepare()

        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
        assertEquals(1f, service.state.value.progress)
    }

    @Test
    fun repeatedPreparationRemainsReady() = runTest {
        val service = DemoModelSetupService()

        service.prepare()
        val readyState = service.state.value
        service.prepare()

        assertEquals(readyState, service.state.value)
        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
    }
}
