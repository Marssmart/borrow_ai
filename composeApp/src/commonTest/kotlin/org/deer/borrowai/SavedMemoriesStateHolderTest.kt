package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.PagerState
import arrow.core.Either
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackRequest
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult

@OptIn(ExperimentalCoroutinesApi::class)
class SavedMemoriesStateHolderTest {
    @Test
    fun memoriesAreSortedNewestFirst() = runTest {
        val older = memory("older", createdAt = 1L)
        val newer = memory("newer", createdAt = 2L)
        val memoryService = FakeMemoryService(MutableStateFlow(listOf(older, newer)))
        val holder = holder(this, memoryService = memoryService)

        assertEquals(listOf(newer, older), holder.memories.first { it.isNotEmpty() })
    }

    @Test
    fun openMemoryIgnoresUnknownMemory() = runTest {
        val holder = holder(this, memoryService = FakeMemoryService(MutableStateFlow(emptyList())))

        holder.openMemory("missing")
        advanceUntilIdle()

        assertNull(holder.selectedMemoryId.value)
    }

    @Test
    fun missingPlaybackTargetMapsToMemoryNotFoundError() = runTest {
        val mediaSession = FakeMediaSessionService(PlaybackError.MemoryNotFound)
        val holder = holder(this, mediaSessionService = mediaSession)

        holder.togglePlayback("memory", PlaybackContent.SUMMARY)
        advanceUntilIdle()
        assertEquals(SavedMemoriesPlaybackError.MEMORY_NOT_FOUND, holder.playbackError.value)

        holder.dismissPlaybackError()
        assertNull(holder.playbackError.value)
    }

    private fun holder(
        testScope: TestScope,
        memoryService: MemoryService = FakeMemoryService(MutableStateFlow(emptyList())),
        mediaSessionService: MediaSessionService = FakeMediaSessionService(null),
    ): SavedMemoriesStateHolder = SavedMemoriesStateHolder(
        memoryService = memoryService,
        mediaSessionService = mediaSessionService,
        pagerState = PagerState(currentPage = 0, pageCount = { SavedMemoriesStateHolder.MEMORY_PAGE_COUNT }),
        memoriesScrollState = ScrollState(0),
        detailScrollState = ScrollState(0),
        scope = testScope.backgroundScope,
    )

    private fun memory(id: String, createdAt: Long) = Memory(
        id = id,
        createdAtEpochMillis = createdAt,
        title = id,
        summary = "",
        transcription = "",
    )

    private class FakeMemoryService(private val state: MutableStateFlow<List<Memory>>) : MemoryService {
        override fun getState(): StateFlow<List<Memory>> = state
        override suspend fun findById(id: String): Either<MemoryError, Memory> = error("unused")
        override suspend fun findAll(): Either<MemoryError, List<Memory>> = error("unused")
        override suspend fun create(draft: MemoryDraft, id: String?): Either<MemoryError, Memory> = error("unused")
        override suspend fun update(id: String, draft: MemoryDraft): Either<MemoryError, Unit> = error("unused")
        override suspend fun delete(id: String): Either<MemoryError, Unit> = error("unused")
        override suspend fun purge(id: String): Boolean = error("unused")
    }

    private class FakeMediaSessionService(private val playbackError: PlaybackError?) : MediaSessionService {
        override val state: StateFlow<MediaSessionState> = MutableStateFlow(MediaSessionState.Idle)
        override fun startRecording(holdToRecord: Boolean) = error("unused")
        override fun stopRecording(): RecordingResult? = error("unused")
        override fun requestPlayback(target: PlaybackTarget): Either<PlaybackError, PlaybackRequest> =
            Either.Left(playbackError ?: error("unused"))
        override fun confirmPendingPlayback(): Either<PlaybackError, PlaybackRequest> = error("unused")
        override fun cancelPendingPlayback(): Boolean = error("unused")
        override fun stopPlayback() = error("unused")
        override fun close() = Unit
    }
}
