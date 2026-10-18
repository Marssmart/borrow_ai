package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackTarget

internal enum class SavedMemoriesPlaybackError {
    MEMORY_NOT_FOUND,
    REQUEST_REJECTED,
    RECORDING_UNAVAILABLE,
}

internal class SavedMemoriesStateHolder(
    private val memoryService: MemoryService,
    private val mediaSessionService: MediaSessionService,
    val pagerState: PagerState,
    val memoriesScrollState: ScrollState,
    val detailScrollState: ScrollState,
    private val scope: CoroutineScope,
) {
    /** Memories sorted newest first, derived from the memory service. */
    val memories: StateFlow<List<Memory>> = memoryService.getState()
        .map { memories -> memories.sortedByDescending(Memory::createdAtEpochMillis) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val mediaSessionState: StateFlow<MediaSessionState> = mediaSessionService.state

    private val mutableSelectedMemoryId = MutableStateFlow<String?>(null)

    /** The memory shown on the detail page. Non-null also means detail swiping is enabled. */
    val selectedMemoryId: StateFlow<String?> = mutableSelectedMemoryId.asStateFlow()

    /** True once the pager has settled on the detail page for the selected memory. */
    val isDetailVisible: Flow<Boolean> = combine(
        selectedMemoryId,
        snapshotFlow { pagerState.settledPage },
    ) { selectedId, settledPage ->
        selectedId != null && settledPage == MEMORY_DETAIL_PAGE
    }.distinctUntilChanged()

    private val mutablePlaybackError = MutableStateFlow<SavedMemoriesPlaybackError?>(null)

    val playbackError: StateFlow<SavedMemoriesPlaybackError?> = mutablePlaybackError.asStateFlow()

    /** Playback buttons are disabled when the media service cannot play recorded media. */
    val playbackAvailable: Boolean get() = mediaSessionService.playbackAvailable

    init {
        scope.launch {
            memories.collect { orderedMemories ->
                val selectedId = mutableSelectedMemoryId.value
                if (selectedId != null && orderedMemories.none { it.id == selectedId }) {
                    closeDetailState()
                    scope.launch { pagerState.animateScrollToPage(MEMORY_LIST_PAGE) }
                }
            }
        }
    }

    fun openMemory(memoryId: String) {
        if (memories.value.none { it.id == memoryId }) return
        mutableSelectedMemoryId.value = memoryId
        scope.launch {
            detailScrollState.scrollTo(0)
            pagerState.animateScrollToPage(MEMORY_DETAIL_PAGE)
        }
    }

    fun onPagerPageSettled(page: Int) {
        if (page == MEMORY_LIST_PAGE && mutableSelectedMemoryId.value != null) {
            closeDetailState()
        }
    }

    fun backToList() {
        scope.launch {
            pagerState.animateScrollToPage(MEMORY_LIST_PAGE)
            closeDetailState()
        }
    }

    fun returnToCapture(onReturnToCapture: () -> Unit) {
        scope.launch {
            pagerState.animateScrollToPage(MEMORY_LIST_PAGE)
            closeDetailState()
            onReturnToCapture()
        }
    }

    fun togglePlayback(memoryId: String, content: PlaybackContent) {
        if (!playbackAvailable) return
        val target = PlaybackTarget(memoryId = memoryId, content = content)
        val currentPlayback = mediaSessionState.value as? MediaSessionState.Playing
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
        mutablePlaybackError.value = null
    }

    private fun closeDetailState() {
        mutableSelectedMemoryId.value = null
    }

    private fun setPlaybackError(error: PlaybackError) {
        mutablePlaybackError.value = error.toUiError()
    }

    private fun PlaybackError.toUiError(): SavedMemoriesPlaybackError = when (this) {
        PlaybackError.MemoryNotFound -> SavedMemoriesPlaybackError.MEMORY_NOT_FOUND
        PlaybackError.RecordingUnavailable -> SavedMemoriesPlaybackError.RECORDING_UNAVAILABLE
        PlaybackError.NoPendingConfirmation -> SavedMemoriesPlaybackError.REQUEST_REJECTED
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
        val MEMORY_LIST_FOOTER_PADDING = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
        val MEMORY_LIST_ITEM_SPACING = 12.dp

        val MEMORY_CARD_SHAPE = RoundedCornerShape(23.dp)
        val MEMORY_CARD_CONTENT_PADDING = PaddingValues(14.dp)
        val MEMORY_CARD_CONTENT_SPACING = 8.dp
        val MEMORY_CARD_GLYPH_SPACING = 10.dp
        val MEMORY_CARD_SURFACE_ALPHA = 0.92f

        val MEMORY_DETAIL_NAVIGATION_PADDING = PaddingValues(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = 0.dp,
        )
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

        val MEMORY_AUDIO_GLYPH_SIZE = 34.dp
        val MEMORY_PLAYBACK_BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
        val MEMORY_PLAYBACK_BUTTON_ICON_SPACING = 4.dp
        val MEMORY_PLAYBACK_BUTTON_BORDER_WIDTH = 1.dp
        const val MEMORY_PLAYBACK_BUTTON_BORDER_ALPHA = 0.24f
    }
}
