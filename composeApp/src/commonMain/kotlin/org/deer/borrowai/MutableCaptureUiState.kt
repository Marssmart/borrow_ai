package org.deer.borrowai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class MutableCaptureUiState(initial: UiState) {
    data class UiState(
        val isRecording: Boolean = false,
        val isHoldRecording: Boolean = false,
        val elapsedSeconds: Int = 0,
        val transcript: String = "",
    )

    private val mutableState = MutableStateFlow(initial)

    val state: StateFlow<UiState> = mutableState.asStateFlow()

    val value: UiState
        get() = mutableState.value

    val isRecording: Boolean
        get() = value.isRecording

    val isHoldRecording: Boolean
        get() = value.isHoldRecording

    fun update(transform: (UiState) -> UiState) {
        mutableState.update(transform)
    }
}
