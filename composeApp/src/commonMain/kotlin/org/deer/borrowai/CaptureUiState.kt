package org.deer.borrowai

import org.deer.borrowai.data.service.MediaSessionState

internal data class CaptureUiState(
    val isRecording: Boolean = false,
    val isHoldRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val transcript: String = "",
)

private const val MILLIS_PER_SECOND = 1_000L

internal fun MediaSessionState.toCaptureUiState(): CaptureUiState =
    when (this) {
        is MediaSessionState.Recording -> CaptureUiState(
            isRecording = true,
            isHoldRecording = holdToRecord,
            elapsedSeconds = (elapsedMillis / MILLIS_PER_SECOND).toInt(),
            transcript = transcription,
        )

        MediaSessionState.Idle, is MediaSessionState.Playing -> CaptureUiState()
    }
