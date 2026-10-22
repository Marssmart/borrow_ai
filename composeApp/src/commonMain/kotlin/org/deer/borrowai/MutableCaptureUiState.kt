package org.deer.borrowai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.deer.borrowai.data.service.recording.LiveTranscriptionFailure

internal class MutableCaptureUiState(initial: UiState) {
    data class UiState(
        val isRecording: Boolean = false,
        val isHoldRecording: Boolean = false,
        val elapsedSeconds: Int = 0,
        val transcript: String = "",
        val liveTranscriptionFailure: LiveTranscriptionFailure? = null,
    )

    private val mutableState = MutableStateFlow(initial)

    val state: StateFlow<UiState> = mutableState.asStateFlow()

    fun value(): UiState = mutableState.value

    fun isRecording(): Boolean = value().isRecording

    fun isHoldRecording(): Boolean = value().isHoldRecording

    fun update(transform: (UiState) -> UiState) {
        mutableState.update(transform)
    }
}
