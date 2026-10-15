package org.deer.borrowai.data.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableModelSetupStateTest {
    @Test
    fun startsNotStarted() {
        val state = MutableModelSetupState()

        assertEquals(ModelSetupPhase.NOT_STARTED, state.value.phase)
        assertEquals(ModelSetupState(), state.value)
    }

    @Test
    fun transitionReplacesAllFields() {
        val state = MutableModelSetupState()
        state.fail(ModelSetupFailureReason.NETWORK)

        state.transition(ModelSetupPhase.DOWNLOADING_LLM, progress = 0.5f, bytesDownloaded = 10L, bytesTotal = 20L)

        assertEquals(
            ModelSetupState(
                phase = ModelSetupPhase.DOWNLOADING_LLM,
                progress = 0.5f,
                bytesDownloaded = 10L,
                bytesTotal = 20L,
            ),
            state.value,
        )
        assertNull(state.value.failureReason)
    }

    @Test
    fun failKeepsProgressAndSetsReason() {
        val state = MutableModelSetupState()
        state.transition(ModelSetupPhase.DOWNLOADING_VOICE, progress = 0.25f)

        state.fail(ModelSetupFailureReason.STORAGE)

        assertEquals(ModelSetupPhase.FAILED, state.value.phase)
        assertEquals(0.25f, state.value.progress)
        assertEquals(ModelSetupFailureReason.STORAGE, state.value.failureReason)
    }

    @Test
    fun stateFlowEmitsLatestValue() {
        val state = MutableModelSetupState()

        state.transition(ModelSetupPhase.READY, progress = 1f)

        assertEquals(ModelSetupPhase.READY, state.state.value.phase)
    }

    @Test
    fun isReadyOnlyWhenPhaseIsReady() {
        val state = MutableModelSetupState()
        assertFalse(state.isReady)

        state.transition(ModelSetupPhase.DOWNLOADING_LLM, progress = 0.5f)
        assertFalse(state.isReady)

        state.transition(ModelSetupPhase.READY, progress = 1f)
        assertTrue(state.isReady)

        state.fail(ModelSetupFailureReason.NETWORK)
        assertFalse(state.isReady)
    }
}
