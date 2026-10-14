package org.deer.borrowai.data.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MutableMediaSessionStateTest {
    private val target = PlaybackTarget(memoryId = "memory-1", content = PlaybackContent.SUMMARY)

    @Test
    fun startsIdle() {
        assertIs<MediaSessionState.Idle>(MutableMediaSessionState().value)
    }

    @Test
    fun transitionRecordingUsesProvidedFields() {
        val state = MutableMediaSessionState()

        state.transitionRecording(elapsedMillis = 5L, transcription = "hi", holdToRecord = true)

        val recording = assertIs<MediaSessionState.Recording>(state.value)
        assertEquals(5L, recording.elapsedMillis)
        assertEquals("hi", recording.transcription)
        assertEquals(true, recording.holdToRecord)
        assertNull(recording.pendingPlayback)
    }

    @Test
    fun transitionPlayingAndIdle() {
        val state = MutableMediaSessionState()

        state.transitionPlaying(target = target, durationMillis = 3_000L)
        val playing = assertIs<MediaSessionState.Playing>(state.value)
        assertEquals(target, playing.target)
        assertEquals(0L, playing.elapsedMillis)
        assertEquals(3_000L, playing.durationMillis)

        state.transitionIdle()
        assertIs<MediaSessionState.Idle>(state.value)
    }

    @Test
    fun ifRecordingRunsOnlyInRecordingState() {
        val state = MutableMediaSessionState()
        state.transitionRecording(elapsedMillis = 7L)

        val result = state.ifRecording { recording -> recording.elapsedMillis * 2 }
        assertEquals(14L, result)

        state.transitionIdle()
        assertNull(state.ifRecording { "should not run" })
    }

    @Test
    fun ifRecordingReadsStateAfterPrepare() {
        val state = MutableMediaSessionState()
        state.transitionRecording(elapsedMillis = 1L, transcription = "old")

        val result = state.ifRecording(
            before = { state.transitionRecording(elapsedMillis = 2L, transcription = "new") },
        ) { recording -> recording.transcription }

        assertEquals("new", result)
    }

    @Test
    fun ifPlayingRunsOnlyInPlayingState() {
        val state = MutableMediaSessionState()
        state.transitionPlaying(target = target, durationMillis = 4_000L)

        val result = state.ifPlaying { playing -> playing.durationMillis }
        assertEquals(4_000L, result)

        state.transitionRecording()
        assertNull(state.ifPlaying { "should not run" })
    }
}
