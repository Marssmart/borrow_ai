package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.deer.borrowai.data.service.ModelSetupPhase

class DemoModelSetupServiceTest {
    @Test
    fun setupIsImmediatelyReadyWithTierModelNames() = runTest {
        val service = DemoModelSetupService()

        service.prepare()

        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
        assertEquals(1f, service.state.value.progress)
        assertNotNull(service.state.value.voiceModelName)
        assertNotNull(service.state.value.llmModelName)
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
