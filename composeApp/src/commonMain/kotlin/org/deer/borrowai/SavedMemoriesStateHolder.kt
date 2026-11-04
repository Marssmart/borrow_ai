package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.deer.borrowai.data.domain.CategoryFilterChip
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemorySearch
import org.deer.borrowai.data.domain.MemorySearchResults
import org.deer.borrowai.data.domain.MemorySearcher
import org.deer.borrowai.data.domain.categoriesInUse
import org.deer.borrowai.data.domain.effectiveCategoryFilter
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackTarget

internal data class SavedMemoriesUiState(
    val memories: List<Memory> = emptyList(),
    val mediaSessionState: MediaSessionState = MediaSessionState.Idle,
    val selectedMemoryId: String? = null,
    val detailSwipeEnabled: Boolean = false,
    val hasOpenedDetail: Boolean = false,
    val playbackError: PlaybackError? = null,
    val summaryModelMissing: Boolean = false,
    val categoryNames: Map<Int, String> = emptyMap(),
    val searchText: String = "",
    val searchTextCut: Boolean = false,
    val searchResults: MemorySearchResults = MemorySearchResults(),
    val pendingDelete: PendingMemoryDelete = PendingMemoryDelete(),
    /** Counts failed deletes. The screen shows an error each time the count goes up. */
    val deleteFailures: Int = 0,
    /** The category chips the user has selected. A selection of a category no memory uses filters nothing. */
    val selectedCategoryIds: Set<Int> = emptySet(),
) {
    /** The memories the list shows. [memories] keeps the hidden ones, so the detail view can slide back with its memory. */
    val visibleMemories: List<Memory>
        get() = memories.filterNot { it.id in pendingDelete.hiddenMemoryIds }

    /** One chip per category that a visible memory uses, in category id order. */
    val categoryChips: List<CategoryFilterChip>
        get() = categoriesInUse(visibleMemories).entries
            .sortedBy { it.key }
            .mapNotNull { (id, count) -> categoryNames[id]?.let { name -> CategoryFilterChip(id, name, count) } }

    /** The selected categories that still have a chip. */
    val activeCategoryIds: Set<Int>
        get() = effectiveCategoryFilter(selectedCategoryIds, categoryChips.mapTo(HashSet()) { it.id })
}

/** The query, the category filter, and the library to search. The result set is recalculated from one of these. */
private data class SearchRequest(
    val memories: List<Memory>,
    val query: String,
    val categories: Set<Int>,
)

internal class SavedMemoriesStateHolder(
    private val memoryService: MemoryService,
    private val mediaSessionService: MediaSessionService,
    summaryModelMissing: StateFlow<Boolean>,
    val pagerState: PagerState,
    val memoriesListState: LazyListState,
    val detailScrollState: ScrollState,
    private val scope: CoroutineScope,
    /** Starts the soft-delete writes through the app, so close waits for them and a write outlives the screen. */
    private val launchWrite: (suspend () -> Unit) -> Unit,
    /** Serial, so the searcher's cache is never used by two searches at once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val mutableUiState = MutableStateFlow(SavedMemoriesUiState())
    private var lastUndoRequestId = 0L
    private val appliedQuery = MutableStateFlow("")
    private val searcher = MemorySearcher()
    private var searchJob: Job? = null

    val uiState = mutableUiState.asStateFlow()

    /** Playback buttons are disabled when the media service cannot play recorded media. */
    val playbackAvailable: Boolean get() = mediaSessionService.playbackAvailable

    /** When false, playback has no pause, seek, or skip, so the detail view shows only play and stop. */
    val playbackControlsAvailable: Boolean get() = mediaSessionService.playbackControlsAvailable

    init {
        scope.launch {
            memoryService.getState().collect { memories ->
                updateMemories(memories)
                refreshCategoryNames()
            }
        }
        scope.launch {
            mediaSessionService.state.collect { mediaSessionState ->
                mutableUiState.update { it.copy(mediaSessionState = mediaSessionState) }
            }
        }
        scope.launch {
            summaryModelMissing.collect { missing ->
                mutableUiState.update { it.copy(summaryModelMissing = missing) }
            }
        }
        observeSearchResults()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSearchResults() {
        scope.launch {
            // Searches the memories the list shows, so a memory waiting out its delete Undo window is not found.
            combine(
                mutableUiState.map { it.visibleMemories }.distinctUntilChanged(),
                appliedQuery,
                mutableUiState.map { it.selectedCategoryIds }.distinctUntilChanged(),
                mutableUiState.map { it.selectedMemoryId != null }.distinctUntilChanged(),
            ) { memories, query, selectedCategories, isDetailOpen ->
                // A null request stops a running search and keeps the result set frozen (AC-5.1).
                if (isDetailOpen) {
                    null
                } else {
                    val inUse = categoriesInUse(memories).keys
                    SearchRequest(memories, query, effectiveCategoryFilter(selectedCategories, inUse))
                }
            }
                .distinctUntilChanged()
                .mapLatest { request ->
                    request?.let { r ->
                        withContext(searchDispatcher) { searcher.search(r.memories, r.query, r.categories) }
                    }
                }
                .filterNotNull()
                .collect { results ->
                    mutableUiState.update { it.copy(searchResults = results) }
                }
        }
    }

    fun openMemory(memoryId: String) {
        if (uiState.value.memories.none { it.id == memoryId }) return
        mutableUiState.update {
            it.copy(
                selectedMemoryId = memoryId,
                detailSwipeEnabled = true,
                hasOpenedDetail = true,
            )
        }
        scope.launch {
            detailScrollState.scrollTo(0)
            pagerState.animateScrollToPage(MEMORY_DETAIL_PAGE)
        }
    }

    fun onPagerTargetPageChanged(page: Int) {
        val state = uiState.value
        when {
            page == MEMORY_LIST_PAGE && state.hasOpenedDetail ->
                mutableUiState.update { it.copy(hasOpenedDetail = false) }
            page == MEMORY_DETAIL_PAGE && state.selectedMemoryId != null && !state.hasOpenedDetail ->
                mutableUiState.update { it.copy(hasOpenedDetail = true) }
        }
    }

    fun onPagerPageSettled(page: Int) {
        if (page == MEMORY_LIST_PAGE && uiState.value.selectedMemoryId != null) {
            closeDetailState()
        }
    }

    fun backToList() {
        scope.launch {
            pagerState.animateScrollToPage(MEMORY_LIST_PAGE)
            closeDetailState()
        }
    }

    fun togglePlayback(memoryId: String, content: PlaybackContent) {
        if (!playbackAvailable) return
        val target = PlaybackTarget(memoryId = memoryId, content = content)
        val currentPlayback = uiState.value.mediaSessionState as? MediaSessionState.Playing
        if (currentPlayback != null && currentPlayback.target == target) {
            when {
                !playbackControlsAvailable -> mediaSessionService.stopPlayback()
                currentPlayback.paused -> mediaSessionService.resumePlayback().fold({ setPlaybackError(it) }, {})
                else -> mediaSessionService.pausePlayback()
            }
            return
        }

        mediaSessionService.requestPlayback(target).fold({ setPlaybackError(it) }, {})
    }

    fun seekPlayback(positionMillis: Long) {
        mediaSessionService.seekPlayback(positionMillis)
    }

    fun skipPlayback(deltaMillis: Long) {
        mediaSessionService.skipPlayback(deltaMillis)
    }

    fun confirmPendingPlayback() {
        mediaSessionService.confirmPendingPlayback().fold({ setPlaybackError(it) }, {})
    }

    fun cancelPendingPlayback() {
        mediaSessionService.cancelPendingPlayback()
    }

    fun dismissPlaybackError() {
        mutableUiState.update { it.copy(playbackError = null) }
    }

    /** Called on each keystroke. The query is applied [MEMORY_SEARCH_DEBOUNCE_MILLIS] after the last one. */
    fun onSearchTextChanged(text: String) {
        val input = MemorySearch.limitInput(text)
        mutableUiState.update {
            it.copy(searchText = input, searchTextCut = MemorySearch.isInputCut(text))
        }
        val typedQuery = MemorySearch.normalizeQuery(input)
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(MEMORY_SEARCH_DEBOUNCE_MILLIS)
            appliedQuery.value = typedQuery
        }
    }

    /** Applies the current text at once, without the typing delay. Used by the Search key. */
    fun submitSearch() {
        searchJob?.cancel()
        appliedQuery.value = MemorySearch.normalizeQuery(uiState.value.searchText)
    }

    fun clearSearch() {
        searchJob?.cancel()
        mutableUiState.update { it.copy(searchText = "", searchTextCut = false) }
        appliedQuery.value = ""
    }

    /** Selects or deselects a category chip. Chips apply at once, without the typing delay. */
    fun toggleCategory(categoryId: Int) {
        mutableUiState.update { state ->
            val selected = state.selectedCategoryIds
            state.copy(selectedCategoryIds = if (categoryId in selected) selected - categoryId else selected + categoryId)
        }
    }

    /** Clears the category chips. The search text is kept. */
    fun clearCategoryFilters() {
        mutableUiState.update { it.copy(selectedCategoryIds = emptySet()) }
    }

    /**
     * Hides [memoryId] at once and opens its Undo window. Playback of the memory stops first. Its soft delete is written
     * when the window ends, or earlier when a commit trigger fires.
     */
    fun requestDelete(memoryId: String) {
        if (uiState.value.memories.none { it.id == memoryId }) return
        stopPlaybackOf(memoryId)
        val requestId = ++lastUndoRequestId
        val updated = applyDeleteStep { it.request(memoryId, requestId) }
        if (updated.undoWindow?.requestId == requestId) startUndoWindow(requestId)
    }

    /** Restores the memory whose Undo window is open. Nothing is written. */
    fun undoDelete(memoryId: String) {
        mutableUiState.update { it.copy(pendingDelete = it.pendingDelete.undo(memoryId)) }
    }

    /**
     * Writes the open window's delete now. Called when the Memories tab is left and when the app goes to the background,
     * so a deleted memory is not left waiting for its window to end.
     */
    fun commitPendingDelete() {
        applyDeleteStep { it.commitPending() }
    }

    private fun startUndoWindow(requestId: Long) {
        scope.launch {
            delay(MEMORY_UNDO_WINDOW_MILLIS)
            applyDeleteStep { it.windowEnded(requestId) }
        }
    }

    /** Applies one delete step to the UI state, starts the write it asks for, and returns the new delete state. */
    private fun applyDeleteStep(step: (PendingMemoryDelete) -> PendingMemoryDelete.Step): PendingMemoryDelete {
        var toWrite: String? = null
        val updated = mutableUiState.updateAndGet { state ->
            val next = step(state.pendingDelete)
            toWrite = next.toWrite
            state.copy(pendingDelete = next.state)
        }
        toWrite?.let(::writeDelete)
        return updated.pendingDelete
    }

    private fun writeDelete(memoryId: String) {
        launchWrite {
            val outcome = deleteOutcome(memoryService.delete(memoryId))
            mutableUiState.update { state ->
                state.copy(
                    pendingDelete = state.pendingDelete.writeFinished(memoryId),
                    deleteFailures = if (outcome == DeleteOutcome.FAILED) {
                        state.deleteFailures + 1
                    } else {
                        state.deleteFailures
                    },
                )
            }
        }
    }

    /** A deleted memory must not keep playing, and its playback confirmation must not start it later. */
    private fun stopPlaybackOf(memoryId: String) {
        when (val state = mediaSessionService.state.value) {
            is MediaSessionState.Playing ->
                if (state.target.memoryId == memoryId) mediaSessionService.stopPlayback()

            is MediaSessionState.Recording ->
                if (state.pendingPlayback?.memoryId == memoryId) mediaSessionService.cancelPendingPlayback()

            else -> Unit
        }
    }

    private fun closeDetailState() {
        mutableUiState.update {
            it.copy(
                selectedMemoryId = null,
                detailSwipeEnabled = false,
                hasOpenedDetail = false,
            )
        }
    }

    private fun updateMemories(memories: List<Memory>) {
        val orderedMemories = memories.sortedByDescending(Memory::createdAtEpochMillis)
        mutableUiState.update { it.copy(memories = orderedMemories) }

        val selectedMemoryId = uiState.value.selectedMemoryId
        if (selectedMemoryId != null && orderedMemories.none { it.id == selectedMemoryId }) {
            closeDetailState()
            scope.launch { pagerState.animateScrollToPage(MEMORY_LIST_PAGE) }
        }
    }

    private suspend fun refreshCategoryNames() {
        memoryService.findCategories().fold({}) { categories ->
            val names = categories.associate { it.id to it.name }
            mutableUiState.update { it.copy(categoryNames = names) }
        }
    }

    private fun setPlaybackError(error: PlaybackError) {
        mutableUiState.update { it.copy(playbackError = error) }
    }

    internal companion object {
        const val MEMORY_PAGE_COUNT = 2
        const val MEMORY_LIST_PAGE = 0
        const val MEMORY_DETAIL_PAGE = 1
        const val MEMORY_PREVIEW_CHAR_LIMIT = 240
        const val MEMORY_SEARCH_DEBOUNCE_MILLIS = 150L
        const val MEMORY_CONTENT_SIZE_ANIMATION_MILLIS = 220

        val MEMORY_LIST_TITLE_PADDING = PaddingValues(
            start = 24.dp,
            top = 16.dp,
            end = 24.dp,
            bottom = 18.dp,
        )
        val MEMORY_LIST_CONTENT_PADDING = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            bottom = 14.dp,
        )
        val MEMORY_LIST_ITEM_SPACING = 12.dp

        val MEMORY_CARD_SHAPE = RoundedCornerShape(23.dp)
        val MEMORY_CARD_CONTENT_PADDING = PaddingValues(14.dp)
        val MEMORY_CARD_CONTENT_SPACING = 8.dp
        val MEMORY_CARD_SURFACE_ALPHA = 0.92f
        val MEMORY_CATEGORY_CHIP_SHAPE = RoundedCornerShape(50)
        val MEMORY_CATEGORY_CHIP_PADDING = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        val MEMORY_CATEGORY_CHIP_SPACING = 6.dp
        const val MEMORY_CATEGORY_CHIP_ALPHA = 0.12f

        val MEMORY_DETAIL_CONTENT_PADDING = PaddingValues(
            start = 22.dp,
            top = 0.dp,
            end = 22.dp,
            bottom = 24.dp,
        )
        val MEMORY_DETAIL_CONTENT_SPACING = 14.dp
        val MEMORY_DETAIL_HEADER_SHAPE = RoundedCornerShape(
            topStart = 25.dp,
            topEnd = 15.dp,
            bottomEnd = 24.dp,
            bottomStart = 15.dp,
        )
        val MEMORY_DETAIL_HEADER_SURFACE_ALPHA = 0.62f
        val MEMORY_DETAIL_PLAYBACK_SHAPE = RoundedCornerShape(18.dp)
        val MEMORY_DETAIL_PLAYBACK_PADDING = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
        val MEMORY_DETAIL_SUMMARY_SHAPE = RoundedCornerShape(
            topStart = 27.dp,
            topEnd = 16.dp,
            bottomEnd = 25.dp,
            bottomStart = 14.dp,
        )
        val MEMORY_DETAIL_SUMMARY_SURFACE_ALPHA = 0.84f
        val MEMORY_DETAIL_TRANSCRIPTION_SHAPE = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 27.dp,
            bottomEnd = 15.dp,
            bottomStart = 26.dp,
        )

        val MEMORY_TEXT_SECTION_SPACING = 6.dp
        val MEMORY_TEXT_LABEL_SPACING = 7.dp
        val MEMORY_TEXT_LABEL_LETTER_SPACING = 0.7.sp
        val MEMORY_SECTION_ICON_SIZE = 22.dp
        val MEMORY_SECTION_ICON_SHAPE = RoundedCornerShape(7.dp)

        val MEMORY_PANEL_DEFAULT_SHAPE = RoundedCornerShape(23.dp)
        val MEMORY_PANEL_DEFAULT_CONTENT_PADDING = PaddingValues(14.dp)
        val MEMORY_PANEL_DEFAULT_ELEVATION = 6.dp
        val MEMORY_PANEL_FLAT_ELEVATION = 0.dp
        val MEMORY_PANEL_BORDER_WIDTH = 1.dp
        const val MEMORY_PANEL_BORDER_ALPHA = 0.96f

        val MEMORY_PENDING_CARD_BRUSH = Brush.verticalGradient(
            listOf(Color(0xFFFFFAF1), Color(0xFFFDF3E2)),
        )
        val MEMORY_PENDING_CARD_BORDER_COLOR = Color(0xFFD6AA6E).copy(alpha = 0.45f)
        const val MEMORY_PENDING_TEXT_ALPHA = 0.8f

        val MEMORY_PLAYBACK_BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
        val MEMORY_PLAYBACK_BUTTON_ICON_SPACING = 4.dp
        val MEMORY_PLAYBACK_BUTTON_BORDER_WIDTH = 1.dp
        const val MEMORY_PLAYBACK_BUTTON_BORDER_ALPHA = 0.24f
    }
}
