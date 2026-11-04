package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.PagerState
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackRequest
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult

class SavedMemoriesDeleteTest {
    @Test
    fun deleteHidesTheMemoryAtOnceAndUndoWritesNothing() = runTest {
        val service = memoryService(memory("a", createdAt = 1), memory("b", createdAt = 2))
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("a")
        runCurrent()
        assertEquals(listOf("b"), visibleIds(holder))

        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS - 1)
        holder.undoDelete("a")
        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS)
        runCurrent()

        assertEquals(emptyList(), service.deleteCalls)
        assertEquals(listOf("b", "a"), visibleIds(holder))
    }

    @Test
    fun windowEndWritesTheDeleteOnce() = runTest {
        val service = memoryService(memory("a", createdAt = 1), memory("b", createdAt = 2))
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("a")
        runCurrent()
        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS)
        runCurrent()
        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS * 2)
        runCurrent()

        assertEquals(listOf("a"), service.deleteCalls)
        assertEquals(listOf("b"), visibleIds(holder))
        assertNull(holder.uiState.value.pendingDelete.undoWindow)
    }

    @Test
    fun secondDeleteCommitsTheFirstAtOnce() = runTest {
        val service = memoryService(memory("a", createdAt = 1), memory("b", createdAt = 2))
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("a")
        runCurrent()
        holder.requestDelete("b")
        runCurrent()

        assertEquals(listOf("a"), service.deleteCalls)
        assertTrue(visibleIds(holder).isEmpty())

        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS)
        runCurrent()
        assertEquals(listOf("a", "b"), service.deleteCalls)
    }

    @Test
    fun leavingTheTabWritesThePendingDeleteNow() = runTest {
        val service = memoryService(memory("a", createdAt = 1), memory("b", createdAt = 2))
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("a")
        runCurrent()
        holder.commitPendingDelete()
        runCurrent()
        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS)
        runCurrent()

        assertEquals(listOf("a"), service.deleteCalls)
        assertEquals(listOf("b"), visibleIds(holder))
    }

    @Test
    fun playingMemoryIsStoppedWhenItIsDeleted() = runTest {
        val service = memoryService(memory("a", createdAt = 1), memory("b", createdAt = 2))
        val media = FakeMediaSession()
        val holder = holder(service, media)
        runCurrent()
        media.play("a")

        holder.requestDelete("b")
        assertEquals(0, media.stopPlaybackCalls)

        holder.requestDelete("a")
        assertEquals(1, media.stopPlaybackCalls)
        assertEquals(MediaSessionState.Idle, media.state.value)
    }

    @Test
    fun failedWriteShowsTheMemoryAgainAndCountsAnError() = runTest {
        val service = memoryService(memory("a", createdAt = 1), memory("b", createdAt = 2))
        service.deleteFailure = MemoryError.Persistence(IllegalStateException("disk full"))
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("a")
        runCurrent()
        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS)
        runCurrent()

        assertEquals(listOf("b", "a"), visibleIds(holder))
        assertEquals(1, holder.uiState.value.deleteFailures)
        assertTrue(holder.uiState.value.pendingDelete.writing.isEmpty())
    }

    @Test
    fun malformedWriteShowsTheMemoryAgain() = runTest {
        val service = memoryService(memory("a", createdAt = 1))
        service.deleteFailure = MemoryError.Malformed("a")
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("a")
        holder.commitPendingDelete()
        runCurrent()

        assertEquals(listOf("a"), visibleIds(holder))
        assertEquals(1, holder.uiState.value.deleteFailures)
    }

    @Test
    fun alreadyDeletedElsewhereCountsAsDone() = runTest {
        val service = memoryService(memory("a", createdAt = 1), memory("b", createdAt = 2))
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("a")
        runCurrent()
        service.delete("a")
        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS)
        runCurrent()

        assertEquals(0, holder.uiState.value.deleteFailures)
        assertEquals(listOf("b"), visibleIds(holder))
        assertTrue(holder.uiState.value.pendingDelete.writing.isEmpty())
    }

    @Test
    fun repeatedDeleteOfTheSameMemoryWritesOnce() = runTest {
        val service = memoryService(memory("a", createdAt = 1), memory("b", createdAt = 2))
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("a")
        holder.requestDelete("a")
        runCurrent()
        advanceTimeBy(MEMORY_UNDO_WINDOW_MILLIS)
        runCurrent()

        assertEquals(listOf("a"), service.deleteCalls)
    }

    @Test
    fun deleteOfUnknownMemoryIsIgnored() = runTest {
        val service = memoryService(memory("a", createdAt = 1))
        val holder = holder(service)
        runCurrent()

        holder.requestDelete("missing")
        runCurrent()

        assertNull(holder.uiState.value.pendingDelete.undoWindow)
        assertEquals(listOf("a"), visibleIds(holder))
    }

    private fun memory(id: String, createdAt: Long) = Memory(
        id = id,
        createdAtEpochMillis = createdAt,
        title = id,
        summary = "",
        transcription = "",
    )

    private fun memoryService(vararg memories: Memory) = FakeMemoryService(
        initialMemories = memories.toList(),
        epochClock = EpochClock { 0L },
    )

    private fun visibleIds(holder: SavedMemoriesStateHolder): List<String> =
        holder.uiState.value.visibleMemories.map(Memory::id)

    private fun TestScope.holder(
        memoryService: MemoryService,
        mediaSession: FakeMediaSession = FakeMediaSession(),
    ) = SavedMemoriesStateHolder(
        memoryService = memoryService,
        mediaSessionService = mediaSession,
        summaryModelMissing = MutableStateFlow(false),
        pagerState = PagerState(pageCount = { 2 }),
        memoriesScrollState = ScrollState(0),
        detailScrollState = ScrollState(0),
        scope = backgroundScope,
        commitScope = this,
    )
}

private class FakeMediaSession : MediaSessionService {
    private val mutableState = MutableStateFlow<MediaSessionState>(MediaSessionState.Idle)
    override val state: StateFlow<MediaSessionState> = mutableState
    var stopPlaybackCalls = 0

    fun play(memoryId: String) {
        mutableState.value = MediaSessionState.Playing(
            target = PlaybackTarget(memoryId = memoryId, content = PlaybackContent.RECORDING),
            elapsedMillis = 0L,
            durationMillis = 1_000L,
        )
    }

    override fun startRecording(holdToRecord: Boolean) = Unit

    override fun stopRecording(): RecordingResult? = null

    override fun requestPlayback(target: PlaybackTarget): Either<PlaybackError, PlaybackRequest> =
        PlaybackRequest.Started.right()

    override fun confirmPendingPlayback(): Either<PlaybackError, PlaybackRequest> =
        PlaybackError.NoPendingConfirmation.left()

    override fun cancelPendingPlayback(): Boolean = false

    override fun stopPlayback() {
        stopPlaybackCalls++
        mutableState.value = MediaSessionState.Idle
    }

    override fun dismissRecordingError() = Unit

    override fun close() = Unit
}
