package org.deer.borrowai

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Keyboard and system back for the Memories list. Scrolling hides the keyboard (AC-4.2). System back hides the keyboard
 * first, then clears a non-empty query, and is only taken while one of those applies (AC-4.5).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MemoryListSearchKeys(
    listState: LazyListState,
    searchText: String,
    onClearSearch: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hideKeyboard = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    val isScrolling = listState.isScrollInProgress
    LaunchedEffect(isScrolling) {
        if (isScrolling) hideKeyboard()
    }
    // The IME inset has height while the keyboard is shown. WindowInsets.isImeVisible is Android-only.
    val backAction = memoryListBackAction(
        keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
        searchText = searchText,
    )
    BackHandler(enabled = backAction != null) {
        when (backAction) {
            MemoryListBackAction.HIDE_KEYBOARD -> hideKeyboard()
            MemoryListBackAction.CLEAR_QUERY -> onClearSearch()
            null -> Unit
        }
    }
}
