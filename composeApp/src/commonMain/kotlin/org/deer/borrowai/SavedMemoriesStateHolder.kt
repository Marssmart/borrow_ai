package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.deer.borrowai.data.domain.Memory
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
)

internal class SavedMemoriesStateHolder(
    private val memoryService: MemoryService,
    private val mediaSessionService: MediaSessionService,
    summaryModelMissing: StateFlow<Boolean>,
    val pagerState: PagerState,
    val memoriesScrollState: ScrollState,
    val detailScrollState: ScrollState,
    private val scope: CoroutineScope,
) {
    private val mutableUiState = MutableStateFlow(SavedMemoriesUiState())

    val uiState = mutableUiState.asStateFlow()

    /** Playback buttons are disabled when the media service cannot play recorded media. */
    val playbackAvailable: Boolean get() = mediaSessionService.playbackAvailable

    init {
        scope.launch {
            memoryService.getState().collect { memories ->
                updateMemories(memories)
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

    fun onPagerPageSettled(page: Int) {
        if (page == MEMORY_LIST_PAGE && uiState.value.hasOpenedDetail) {
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
        if (currentPlayback?.target == target) {
            mediaSessionService.stopPlayback()
            return
        }

        mediaSessionService.requestPlayback(target).fold({ setPlaybackError(it) }, {})
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

    private fun setPlaybackError(error: PlaybackError) {
        mutableUiState.update { it.copy(playbackError = error) }
    }

    internal companion object {
        const val MEMORY_PAGE_COUNT = 2
        const val MEMORY_LIST_PAGE = 0
        const val MEMORY_DETAIL_PAGE = 1
        const val MEMORY_PREVIEW_CHAR_LIMIT = 240
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
        val MEMORY_CARD_GLYPH_SPACING = 10.dp
        val MEMORY_CARD_SURFACE_ALPHA = 0.92f

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

        val MEMORY_AUDIO_GLYPH_SIZE = 34.dp
        val MEMORY_PLAYBACK_BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
        val MEMORY_PLAYBACK_BUTTON_ICON_SPACING = 4.dp
        val MEMORY_PLAYBACK_BUTTON_BORDER_WIDTH = 1.dp
        const val MEMORY_PLAYBACK_BUTTON_BORDER_ALPHA = 0.24f
    }
}
