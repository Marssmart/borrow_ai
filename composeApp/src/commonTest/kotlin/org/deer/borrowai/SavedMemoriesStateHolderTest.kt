package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import arrow.core.Either
import arrow.core.left
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.CategoryFilterChip
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.MemorySearch
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackRequest
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult

@OptIn(ExperimentalCoroutinesApi::class)
class SavedMemoriesStateHolderTest {
    private val debounce = SavedMemoriesStateHolder.MEMORY_SEARCH_DEBOUNCE_MILLIS

    @Test
    fun resultsStartWithEveryMemoryNewestFirst() = runTest {
        val holder = holder(this, libraryMemories())

        runCurrent()

        assertEquals(listOf("pending", "dentist", "budget"), resultIds(holder))
        assertEquals(3, holder.uiState.value.memories.size)
        assertFalse(holder.uiState.value.searchResults.isFiltered)
    }

    @Test
    fun typedQueryIsAppliedOnlyAfterTheDebounce() = runTest {
        val holder = holder(this, libraryMemories())
        runCurrent()

        holder.onSearchTextChanged("budget")
        advanceTimeBy(debounce - 1)
        runCurrent()
        assertEquals(listOf("pending", "dentist", "budget"), resultIds(holder))

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("budget"), resultIds(holder))
        assertTrue(holder.uiState.value.searchResults.isFiltered)
    }

    @Test
    fun typingRestartsTheDebounceFromTheLastKeystroke() = runTest {
        val holder = holder(this, libraryMemories())
        runCurrent()

        holder.onSearchTextChanged("b")
        advanceTimeBy(debounce - 50)
        runCurrent()
        holder.onSearchTextChanged("bu")
        advanceTimeBy(debounce - 50)
        runCurrent()
        assertEquals(listOf("pending", "dentist", "budget"), resultIds(holder))

        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf("budget"), resultIds(holder))
    }

    @Test
    fun submitSearchAppliesTheTextAtOnce() = runTest {
        val holder = holder(this, libraryMemories())
        runCurrent()

        holder.onSearchTextChanged("  dentist ")
        holder.submitSearch()
        runCurrent()

        assertEquals(listOf("dentist"), resultIds(holder))
        assertEquals("dentist", holder.uiState.value.searchResults.query)
        advanceTimeBy(debounce)
        runCurrent()
        assertEquals(listOf("dentist"), resultIds(holder))
    }

    @Test
    fun clearSearchEmptiesTheFieldAndShowsEveryMemoryAtOnce() = runTest {
        val holder = holder(this, libraryMemories())
        runCurrent()
        holder.onSearchTextChanged("budget")
        holder.submitSearch()
        runCurrent()

        holder.clearSearch()
        runCurrent()

        assertEquals("", holder.uiState.value.searchText)
        assertEquals(listOf("pending", "dentist", "budget"), resultIds(holder))
        assertFalse(holder.uiState.value.searchResults.isFiltered)
    }

    @Test
    fun typedTextIsCutAt100CharactersAndFlagged() = runTest {
        val holder = holder(this, libraryMemories())
        runCurrent()
        // Without the cut the query would be "budget zzz", which matches nothing.
        val pasted = "budget" + " ".repeat(MemorySearch.MAX_QUERY_LENGTH) + "zzz"

        holder.onSearchTextChanged(pasted)
        advanceTimeBy(debounce)
        runCurrent()

        assertEquals(MemorySearch.MAX_QUERY_LENGTH, holder.uiState.value.searchText.length)
        assertTrue(holder.uiState.value.searchTextCut)
        assertEquals("budget", holder.uiState.value.searchResults.query)
        assertEquals(listOf("budget"), resultIds(holder))
    }

    @Test
    fun libraryChangesRecalculateAnActiveSearch() = runTest {
        val memoryService = FakeMemoryService(libraryMemories(), EpochClock { 0L })
        val holder = holder(this, memoryService)
        runCurrent()
        holder.onSearchTextChanged("insurance")
        holder.submitSearch()
        runCurrent()
        assertEquals(emptyList(), resultIds(holder))

        memoryService.create(MemoryDraft(title = "", summary = "", transcription = "Call the insurance company"))
        runCurrent()

        assertEquals(1, resultIds(holder).size)
        assertEquals(4, holder.uiState.value.memories.size)
    }

    @Test
    fun resultsDoNotChangeWhileTheDetailIsOpenAndAreRecalculatedOnReturn() = runTest {
        val memoryService = FakeMemoryService(libraryMemories(), EpochClock { 0L })
        val holder = holder(this, memoryService)
        runCurrent()
        holder.openMemory("budget")
        runCurrent()

        memoryService.create(MemoryDraft(title = "", summary = "", transcription = "New budget note"))
        runCurrent()

        assertEquals(listOf("pending", "dentist", "budget"), resultIds(holder))
        assertEquals(4, holder.uiState.value.memories.size)

        holder.onPagerPageSettled(SavedMemoriesStateHolder.MEMORY_LIST_PAGE)
        runCurrent()

        assertEquals(4, resultIds(holder).size)
    }

    @Test
    fun memoryHiddenByAPendingDeleteIsNotSearchedUntilRestored() = runTest {
        val holder = holder(this, libraryMemories())
        runCurrent()
        holder.onSearchTextChanged("budget")
        holder.submitSearch()
        runCurrent()
        assertEquals(listOf("budget"), resultIds(holder))

        holder.requestDelete("budget")
        runCurrent()
        assertEquals(emptyList(), resultIds(holder))

        holder.undoDelete("budget")
        runCurrent()
        assertEquals(listOf("budget"), resultIds(holder))
    }

    @Test
    fun openingTheDetailCancelsASearchThatIsStillRunning() = runTest {
        val searches = QueuedDispatcher()
        val holder = holder(this, libraryMemories(), searches)
        runCurrent()
        searches.runQueued()
        runCurrent()
        val allIds = resultIds(holder)

        holder.onSearchTextChanged("budget")
        holder.submitSearch()
        runCurrent()
        holder.openMemory("dentist")
        runCurrent()
        searches.runQueued()
        runCurrent()

        assertEquals(allIds, resultIds(holder))
    }

    @Test
    fun chipsListTheCategoriesInUseInIdOrderWithCounts() = runTest {
        val holder = categorizedHolder()
        runCurrent()

        assertEquals(
            listOf(CategoryFilterChip(1, "Work", 2), CategoryFilterChip(2, "Travel", 1)),
            holder.uiState.value.categoryChips,
        )
    }

    @Test
    fun selectingChipsCombinesWithOrAndAppliesAtOnce() = runTest {
        val holder = categorizedHolder()
        runCurrent()

        holder.toggleCategory(2)
        runCurrent()
        assertEquals(listOf("m2"), resultIds(holder))

        holder.toggleCategory(1)
        runCurrent()
        assertEquals(listOf("m2", "m1"), resultIds(holder))
    }

    @Test
    fun chipsCombineWithTheSearchAsAnd() = runTest {
        val holder = categorizedHolder()
        runCurrent()

        holder.toggleCategory(1)
        holder.onSearchTextChanged("trip")
        holder.submitSearch()
        runCurrent()

        assertEquals(listOf("m2"), resultIds(holder))
    }

    @Test
    fun clearingFiltersRemovesTheChipsButKeepsTheSearch() = runTest {
        val holder = categorizedHolder()
        runCurrent()
        holder.onSearchTextChanged("budget")
        holder.submitSearch()
        holder.toggleCategory(2)
        runCurrent()
        assertEquals(listOf("m2"), resultIds(holder))

        holder.clearCategoryFilters()
        runCurrent()

        assertEquals(listOf("m2", "m1"), resultIds(holder))
        assertEquals(emptySet(), holder.uiState.value.selectedCategoryIds)
        assertEquals("budget", holder.uiState.value.searchText)
    }

    @Test
    fun aSelectedCategoryNoLongerInUseFiltersNothing() = runTest {
        val holder = categorizedHolder()
        runCurrent()

        holder.toggleCategory(3)
        runCurrent()

        assertEquals(listOf("m3", "m2", "m1"), resultIds(holder))
        assertEquals(emptySet(), holder.uiState.value.activeCategoryIds)
    }

    @Test
    fun chipsDoNotChangeTheResultsWhileTheDetailIsOpen() = runTest {
        val holder = categorizedHolder()
        runCurrent()
        holder.openMemory("m1")
        runCurrent()

        holder.toggleCategory(2)
        runCurrent()
        assertEquals(listOf("m3", "m2", "m1"), resultIds(holder))

        holder.onPagerPageSettled(SavedMemoriesStateHolder.MEMORY_LIST_PAGE)
        runCurrent()
        assertEquals(listOf("m2"), resultIds(holder))
    }

    private suspend fun TestScope.categorizedHolder(): SavedMemoriesStateHolder {
        val memoryService = FakeMemoryService(categorizedMemories(), EpochClock { 0L })
        memoryService.createCategory("Work", "")
        memoryService.createCategory("Travel", "")
        memoryService.createCategory("Ideas", "")
        return holder(this, memoryService)
    }

    private fun categorizedMemories(): List<Memory> = listOf(
        memory("m1", createdAt = 1, transcription = "budget notes", categories = listOf(1)),
        memory("m2", createdAt = 2, transcription = "trip budget", categories = listOf(1, 2)),
        memory("m3", createdAt = 3, transcription = "dentist"),
    )

    private fun libraryMemories(): List<Memory> = listOf(
        memory("budget", createdAt = 1, transcription = "Quarterly budget numbers", title = "Budget review"),
        memory("dentist", createdAt = 2, transcription = "Book the dentist", title = "Dentist"),
        memory("pending", createdAt = 3),
    )

    private fun memory(
        id: String,
        createdAt: Long,
        title: String = "",
        transcription: String = "",
        categories: List<Int> = emptyList(),
    ): Memory = Memory(
        id = id,
        createdAtEpochMillis = createdAt,
        title = title,
        summary = "",
        transcription = transcription,
        transcriptionStatus = if (transcription.isBlank()) TranscriptionStatus.PENDING else TranscriptionStatus.DONE,
        categories = categories,
    )

    private fun resultIds(holder: SavedMemoriesStateHolder): List<String> =
        holder.uiState.value.searchResults.memories.map(Memory::id)

    private fun holder(
        scope: TestScope,
        memories: List<Memory>,
        searchDispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
    ): SavedMemoriesStateHolder = holder(scope, FakeMemoryService(memories, EpochClock { 0L }), searchDispatcher)

    private fun holder(
        scope: TestScope,
        memoryService: FakeMemoryService,
        searchDispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
    ): SavedMemoriesStateHolder =
        SavedMemoriesStateHolder(
            memoryService = memoryService,
            mediaSessionService = FakeMediaSession(),
            summaryModelMissing = MutableStateFlow(false),
            pagerState = PagerState(pageCount = { SavedMemoriesStateHolder.MEMORY_PAGE_COUNT }),
            memoriesListState = LazyListState(),
            detailScrollState = ScrollState(0),
            scope = scope.backgroundScope,
            launchWrite = { block -> scope.launch { block() } },
            searchDispatcher = searchDispatcher,
        )

    /** Holds dispatched work until [runQueued], so a test decides when a search runs. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private class FakeMediaSession : MediaSessionService {
        override val state: StateFlow<MediaSessionState> = MutableStateFlow(MediaSessionState.Idle)

        override fun startRecording(holdToRecord: Boolean) = Unit

        override fun stopRecording(): RecordingResult? = null

        override fun requestPlayback(target: PlaybackTarget): Either<PlaybackError, PlaybackRequest> =
            PlaybackError.MemoryNotFound.left()

        override fun confirmPendingPlayback(): Either<PlaybackError, PlaybackRequest> =
            PlaybackError.NoPendingConfirmation.left()

        override fun cancelPendingPlayback(): Boolean = false

        override fun stopPlayback() = Unit

        override fun dismissRecordingError() = Unit

        override fun close() = Unit
    }
}
