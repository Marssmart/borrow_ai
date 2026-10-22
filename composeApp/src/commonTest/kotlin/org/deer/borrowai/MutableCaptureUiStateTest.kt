package org.deer.borrowai

import org.deer.borrowai.data.service.recording.LiveTranscriptionFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableCaptureUiStateTest {
    @Test
    fun exposesInitialStateThroughStateAndValue() {
        val initial = MutableCaptureUiState.UiState(transcript = "hello")
        val uiState = MutableCaptureUiState(initial)

        assertEquals(initial, uiState.value())
        assertEquals(initial, uiState.state.value)
    }

    @Test
    fun recordingFlagsReflectCurrentState() {
        val uiState = MutableCaptureUiState(MutableCaptureUiState.UiState())
        assertFalse(uiState.isRecording())
        assertFalse(uiState.isHoldRecording())

        uiState.update { it.copy(isRecording = true, isHoldRecording = true) }

        assertTrue(uiState.isRecording())
        assertTrue(uiState.isHoldRecording())
    }

    @Test
    fun updateAppliesTransformAndPublishesToState() {
        val uiState = MutableCaptureUiState(MutableCaptureUiState.UiState())

        uiState.update { it.copy(elapsedSeconds = 7) }

        assertEquals(7, uiState.value().elapsedSeconds)
        assertEquals(7, uiState.state.value.elapsedSeconds)
    }

    @Test
    fun liveTranscriptionFailureDefaultsToNullAndPublishesThroughState() {
        val uiState = MutableCaptureUiState(MutableCaptureUiState.UiState())
        assertNull(uiState.value().liveTranscriptionFailure)

        uiState.update { it.copy(liveTranscriptionFailure = LiveTranscriptionFailure.AUDIO_INTERRUPTED) }

        assertEquals(LiveTranscriptionFailure.AUDIO_INTERRUPTED, uiState.state.value.liveTranscriptionFailure)
    }
}
