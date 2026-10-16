package org.deer.borrowai

import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AppShellUiState(
    val isMemoryDetailVisible: Boolean = false,
)

internal class AppShellStateHolder(
    val mainPagerState: PagerState,
    private val scope: CoroutineScope,
) {
    private val mutableUiState = MutableStateFlow(AppShellUiState())

    val uiState = mutableUiState.asStateFlow()

    fun setMemoryDetailVisible(visible: Boolean) {
        mutableUiState.update { it.copy(isMemoryDetailVisible = visible) }
    }

    fun returnToCapture() {
        scope.launch { mainPagerState.animateScrollToPage(CAPTURE_PAGE) }
    }

    internal companion object {
        const val MAIN_PAGE_COUNT = 2
        const val CAPTURE_PAGE = 0
        const val MEMORIES_PAGE = 1
        val BACKGROUND_TOP_GLOW_SIZE = 380.dp
        val BACKGROUND_BOTTOM_GLOW_SIZE = 440.dp
    }
}
