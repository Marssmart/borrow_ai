package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackRequestResult
import org.deer.borrowai.data.service.PlaybackTarget

@OptIn(ExperimentalCoroutinesApi::class)
class DemoMediaSessionServiceTest {
    @Test
    fun recordingAndPlaybackTransitionsAreExclusiveAndConfirmationAware() = runTest {
        val memories = InMemoryDemoMemoryService(
            initialMemories = emptyList(),
            epochClock = EpochClock { 1_800_000_000_000L },
        )
        val memory = memories.create(MemoryDraft("Note", "Summary", "Transcription"))
        val service = DemoMediaSessionService(memories, scope = backgroundScope)
        val target = PlaybackTarget(memory.id, PlaybackContent.TRANSCRIPTION)

        assertTrue(service.idleTranscription.value.startsWith("Lorem ipsum dolor sit amet"))
        service.startRecording(holdToRecord = true)
        assertTrue(assertIs<MediaSessionState.Recording>(service.state.value).holdToRecord)
        assertEquals("", assertIs<MediaSessionState.Recording>(service.state.value).transcription)
        advanceTimeBy(45)
        runCurrent()
        assertEquals(
            "T",
            assertIs<MediaSessionState.Recording>(service.state.value).transcription,
        )
        assertEquals(PlaybackRequestResult.ConfirmationRequired(target), service.requestPlayback(target))
        assertEquals(target, assertIs<MediaSessionState.Recording>(service.state.value).pendingPlayback)

        service.cancelPendingPlayback()
        val recordingAfterCancel = assertIs<MediaSessionState.Recording>(service.state.value)
        assertNull(recordingAfterCancel.pendingPlayback)
        assertEquals(PlaybackRequestResult.ConfirmationRequired(target), service.requestPlayback(target))
        assertEquals(PlaybackRequestResult.Started, service.confirmPendingPlayback())
        assertEquals(target, assertIs<MediaSessionState.Playing>(service.state.value).target)
        assertEquals("T", service.idleTranscription.value)

        service.startRecording()
        assertFalse(assertIs<MediaSessionState.Recording>(service.state.value).holdToRecord)
        assertEquals(0L, service.stopRecording()?.elapsedMillis)
        assertEquals("", service.idleTranscription.value)
        assertIs<MediaSessionState.Idle>(service.state.value)
    }
}
