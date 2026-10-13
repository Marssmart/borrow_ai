package org.deer.borrowai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.PlaybackRequestResult
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult

class CaptureUiStateHolderTest {
    @Test
    fun recordingActionsAreBlockedUntilModelsAreReady() {
        val mediaSession = FakeMediaSessionService()
        val holder = CaptureUiStateHolder(mediaSession)

        holder.toggleRecording()
        holder.startHoldRecording()
        assertEquals(0, mediaSession.startCount)
        assertFalse(holder.uiState.value.isRecording)

        holder.setModelReady(true)
        holder.startHoldRecording()
        assertEquals(1, mediaSession.startCount)
        assertTrue(mediaSession.lastHoldToRecord)
        assertTrue(holder.uiState.value.isRecording)
    }

    private class FakeMediaSessionService : MediaSessionService {
        private val mutableState = MutableStateFlow<MediaSessionState>(MediaSessionState.Idle)
        override val state: StateFlow<MediaSessionState> = mutableState
        override val idleTranscription = MutableStateFlow("")
        var startCount = 0
            private set
        var lastHoldToRecord = false
            private set

        override fun startRecording(holdToRecord: Boolean) {
            startCount++
            lastHoldToRecord = holdToRecord
            mutableState.value = MediaSessionState.Recording(
                elapsedMillis = 0L,
                transcription = "",
                holdToRecord = holdToRecord,
            )
        }

        override fun stopRecording(): RecordingResult? {
            mutableState.value = MediaSessionState.Idle
            return RecordingResult(elapsedMillis = 0L, transcription = "")
        }

        override fun requestPlayback(target: PlaybackTarget) = PlaybackRequestResult.MemoryNotFound
        override fun confirmPendingPlayback() = PlaybackRequestResult.NoPendingConfirmation
        override fun cancelPendingPlayback() = false
        override fun stopPlayback() = Unit
        override fun close() = Unit
    }
}
