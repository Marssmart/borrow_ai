package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.ModelSetupPhase

@OptIn(ExperimentalCoroutinesApi::class)
class DemoModelSetupServiceTest {
    @Test
    fun setupRunsForFortyEightStepsOfOneHundredMillisecondsByDefault() = runTest {
        val service = DemoModelSetupService()
        val setup = backgroundScope.launch { service.prepare() }

        runCurrent()
        assertNotEquals(ModelSetupPhase.READY, service.state.value.phase)
        advanceTimeBy(4_799)
        runCurrent()
        assertNotEquals(ModelSetupPhase.READY, service.state.value.phase)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
        setup.cancel()
    }

    @Test
    fun namesStayHiddenWhileChoosingAndAppearAfterwards() = runTest {
        val service = DemoModelSetupService(stepDelayMillis = 100)
        val setup = backgroundScope.launch { service.prepare() }

        runCurrent()
        assertEquals(ModelSetupPhase.CHOOSING_MODEL, service.state.value.phase)
        assertNull(service.state.value.voiceModelName)
        assertNull(service.state.value.llmModelName)

        advanceTimeBy(1_200)
        runCurrent()
        assertEquals(ModelSetupPhase.DOWNLOADING_VOICE, service.state.value.phase)
        assertEquals("Whisper Base", service.state.value.voiceModelName)
        assertEquals("Gemma 7B", service.state.value.llmModelName)

        advanceTimeBy(4_800)
        runCurrent()
        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
        assertEquals("Gemma 7B", service.state.value.llmModelName)
        setup.cancel()
    }

    @Test
    fun stagesRunInOrderWithMilestoneProgress() = runTest {
        val service = DemoModelSetupService(stepDelayMillis = 100)
        val setup = backgroundScope.launch { service.prepare() }
        val phases = mutableListOf<ModelSetupPhase>()
        val progressAtPhaseStart = mutableMapOf<ModelSetupPhase, Float>()
        var lastPhase: ModelSetupPhase? = null

        for (tick in 0 until 48) {
            runCurrent()
            val current = service.state.value
            if (current.phase != lastPhase) {
                phases += current.phase
                progressAtPhaseStart[current.phase] = current.progress
                lastPhase = current.phase
            }
            advanceTimeBy(100)
        }
        runCurrent()

        assertEquals(
            listOf(
                ModelSetupPhase.CHOOSING_MODEL,
                ModelSetupPhase.DOWNLOADING_VOICE,
                ModelSetupPhase.LOADING_VOICE,
                ModelSetupPhase.DOWNLOADING_LLM,
            ),
            phases,
        )
        assertTrue(progressAtPhaseStart.getValue(ModelSetupPhase.DOWNLOADING_LLM) > 0.75f)
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

        advanceTimeBy(4_700)
        runCurrent()
        assertEquals(ModelSetupPhase.READY, service.state.value.phase)
        resumedSetup.cancel()
    }
}
