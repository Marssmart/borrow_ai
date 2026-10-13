package org.deer.borrowai

import kotlin.test.Test
import kotlin.test.assertEquals
import org.deer.borrowai.data.service.ModelSetupPhase

class ModelStepLabelRuleTest {
    private fun visibleSteps(phase: ModelSetupPhase, failedDuring: ModelSetupPhase? = null): List<Int> =
        (0..READY_STEP_INDEX).filter { isStepLabelVisible(phase, failedDuring, it) }

    @Test
    fun choosingShowsOnlyTheChoosingLabelPlusReady() {
        assertEquals(listOf(0, READY_STEP_INDEX), visibleSteps(ModelSetupPhase.CHOOSING_MODEL))
        assertEquals(listOf(0, READY_STEP_INDEX), visibleSteps(ModelSetupPhase.NOT_STARTED))
    }

    @Test
    fun completedAndActiveStepsShowLabelsAndFutureStepsStayHidden() {
        assertEquals(listOf(0, 1, READY_STEP_INDEX), visibleSteps(ModelSetupPhase.DOWNLOADING_VOICE))
        assertEquals(listOf(0, 1, 2, READY_STEP_INDEX), visibleSteps(ModelSetupPhase.LOADING_VOICE))
        assertEquals(listOf(0, 1, 2, 3, READY_STEP_INDEX), visibleSteps(ModelSetupPhase.DOWNLOADING_LLM))
    }

    @Test
    fun readyShowsEveryLabel() {
        assertEquals((0..READY_STEP_INDEX).toList(), visibleSteps(ModelSetupPhase.READY))
    }

    @Test
    fun failedSetupKeepsTheStepThatFailedVisible() {
        assertEquals(
            listOf(0, 1, READY_STEP_INDEX),
            visibleSteps(ModelSetupPhase.FAILED, failedDuring = ModelSetupPhase.DOWNLOADING_VOICE),
        )
        assertEquals(
            listOf(0, READY_STEP_INDEX),
            visibleSteps(ModelSetupPhase.FAILED, failedDuring = ModelSetupPhase.CHOOSING_MODEL),
        )
        assertEquals(listOf(0, READY_STEP_INDEX), visibleSteps(ModelSetupPhase.FAILED))
    }
}
