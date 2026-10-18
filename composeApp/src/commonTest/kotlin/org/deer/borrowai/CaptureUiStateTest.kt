package org.deer.borrowai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.MediaSessionState

class CaptureUiStateTest {
    @Test
    fun idleMapsToNotRecording() {
        val state = MediaSessionState.Idle.toCaptureUiState()

        assertFalse(state.isRecording)
        assertFalse(state.isHoldRecording)
        assertEquals(0, state.elapsedSeconds)
        assertEquals("", state.transcript)
    }

    @Test
    fun tapRecordingMapsToRecordingWithoutHold() {
        val state = MediaSessionState.Recording(
            elapsedMillis = 4_999L,
            transcription = "hello",
            holdToRecord = false,
        ).toCaptureUiState()

        assertTrue(state.isRecording)
        assertFalse(state.isHoldRecording)
        assertEquals(4, state.elapsedSeconds)
        assertEquals("hello", state.transcript)
    }

    @Test
    fun holdRecordingMapsToHoldRecording() {
        val state = MediaSessionState.Recording(
            elapsedMillis = 0L,
            transcription = "",
            holdToRecord = true,
        ).toCaptureUiState()

        assertTrue(state.isRecording)
        assertTrue(state.isHoldRecording)
    }
}
