package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState

internal class CaptureUiStateHolder(
    private val mediaSessionService: MediaSessionService,
    initiallyModelReady: Boolean = false,
) {
    data class UiState(
        val isRecording: Boolean = false,
        val isHoldRecording: Boolean = false,
        val elapsedSeconds: Int = 0,
        val transcript: String = "",
    )

    val transcriptScrollState = ScrollState(0)

    private val mutableUiState = MutableStateFlow(
        mediaSessionService.state.value.toUiState(mediaSessionService.idleTranscription.value),
    )

    val uiState: StateFlow<UiState> = mutableUiState.asStateFlow()
    private var isModelReady = initiallyModelReady

    fun setModelReady(ready: Boolean) {
        isModelReady = ready
    }

    suspend fun observeMediaSession() {
        combine(mediaSessionService.state, mediaSessionService.idleTranscription) { session, idleTranscript ->
            session to idleTranscript
        }.collect { (session, idleTranscript) ->
            bindMediaSession(session, idleTranscript)
        }
    }

    fun toggleRecording() {
        if (mutableUiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording(holdToRecord = false)
        }
    }

    fun startHoldRecording() {
        if (!mutableUiState.value.isRecording) {
            startRecording(holdToRecord = true)
        }
    }

    fun stopHoldRecording() {
        if (mutableUiState.value.isRecording && mutableUiState.value.isHoldRecording) {
            stopRecording()
        }
    }

    private fun startRecording(holdToRecord: Boolean) {
        if (!isModelReady) return
        mediaSessionService.startRecording(holdToRecord)
        bindMediaSession(
            mediaSessionService.state.value,
            mediaSessionService.idleTranscription.value,
        )
    }

    private fun stopRecording() {
        val result = mediaSessionService.stopRecording() ?: return
        mutableUiState.value = mutableUiState.value.copy(
            isRecording = false,
            isHoldRecording = false,
            elapsedSeconds = (result.elapsedMillis / MILLIS_PER_SECOND).toInt(),
            transcript = mediaSessionService.idleTranscription.value,
        )
    }

    private fun bindMediaSession(
        mediaSessionState: MediaSessionState,
        idleTranscript: String,
    ) {
        val currentUiState = mutableUiState.value
        mutableUiState.value = when (mediaSessionState) {
            MediaSessionState.Idle -> currentUiState.copy(
                isRecording = false,
                isHoldRecording = false,
                transcript = idleTranscript,
            )

            is MediaSessionState.Recording -> mediaSessionState.toUiState(idleTranscript)

            is MediaSessionState.Playing -> currentUiState.copy(
                isRecording = false,
                isHoldRecording = false,
                transcript = idleTranscript,
            )
        }
    }

    private fun MediaSessionState.toUiState(idleTranscript: String): UiState =
        when (this) {
            MediaSessionState.Idle -> UiState(transcript = idleTranscript)
            is MediaSessionState.Recording -> UiState(
                isRecording = true,
                isHoldRecording = holdToRecord,
                elapsedSeconds = (elapsedMillis / MILLIS_PER_SECOND).toInt(),
                transcript = transcription,
            )

            is MediaSessionState.Playing -> UiState(transcript = idleTranscript)
        }

    internal companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val IDLE_BUTTON_PULSE_SCALE = 1.05f
        const val RECORDING_BUTTON_PULSE_SCALE = 1.1f
        const val IDLE_BUTTON_PULSE_MILLIS = 800
        const val RECORDING_BUTTON_PULSE_MILLIS = 650
        const val IDLE_RING_PULSE_MILLIS = 1_600
        const val RECORDING_RING_PULSE_MILLIS = 1_300
        const val COLOR_TRANSITION_MILLIS = 220
        const val RECORDING_RING_ALPHA = 0.48f
        const val IDLE_RING_ALPHA = 0.34f
        const val RECORDING_BORDER_ALPHA = 0.2f
        const val BUTTON_HALO_ALPHA = 0.58f
        const val RECORDING_TEXT_ALPHA = 0.9f
        const val MEMORY_HINT_ALPHA = 0.75f
        const val FOOTER_WEIGHT = 0.45f
        const val TRANSCRIPT_WEIGHT = 1f
        const val GLYPH_STROKE_WIDTH_FRACTION = 0.055f
        const val GLYPH_MIC_WIDTH_FRACTION = 0.25f
        const val GLYPH_MIC_HEIGHT_FRACTION = 0.43f
        const val GLYPH_MIC_TOP_FRACTION = 0.08f
        const val GLYPH_ARCH_LEFT_FRACTION = 0.22f
        const val GLYPH_ARCH_TOP_FRACTION = 0.30f
        const val GLYPH_ARCH_WIDTH_FRACTION = 0.56f
        const val GLYPH_ARCH_HEIGHT_FRACTION = 0.48f
        const val GLYPH_STEM_TOP_FRACTION = 0.76f
        const val GLYPH_STEM_BOTTOM_FRACTION = 0.91f
        const val GLYPH_BASE_LEFT_FRACTION = 0.34f
        const val GLYPH_BASE_RIGHT_FRACTION = 0.66f
        const val GLYPH_BASE_Y_FRACTION = 0.92f
        const val GLYPH_ARCH_START_ANGLE = 0f
        const val GLYPH_ARCH_SWEEP_ANGLE = 180f

        val TRANSCRIPT_HORIZONTAL_PADDING = 4.dp
        val TRANSCRIPT_VERTICAL_PADDING = 18.dp
        val TRANSCRIPT_LINE_HEIGHT = 25.sp
        val CONTENT_HORIZONTAL_PADDING = 22.dp
        val MICROPHONE_WIDTH_FRACTION = 0.8f
        val FOOTER_TOP_PADDING = 16.dp
        val FOOTER_BOTTOM_PADDING = 10.dp
        val FOOTER_ITEM_SPACING = 5.dp
        val STATUS_LETTER_SPACING = 0.6.sp
        val MEMORY_HINT_FONT_SIZE = 9.sp
        val MEMORY_HINT_LETTER_SPACING = 0.7.sp
        val BUTTON_EXTRA_SIZE = 4.dp
        val BUTTON_RADIUS_INSET = 10.dp
        val BUTTON_CONTENT_INSET = 10.dp
        val BUTTON_HALO_EXTRA_RADIUS = 4.dp
        val BUTTON_CONTENT_PADDING = 20.dp
        val BUTTON_GLYPH_SIZE = 62.dp
        val BUTTON_GLYPH_SPACING = 11.dp
        val RECORDING_DURATION_SPACING = 4.dp
        val BUTTON_BORDER_WIDTH = 8.dp
        val BUTTON_SHADOW_ELEVATION = 0.dp
        val RING_STROKE_WIDTH = 2.dp
        val RING_INITIAL_RADIUS = 8.dp
        val RING_WAVE_EXTENT = 28.dp
        val BUTTON_CONTENT_FONT_SPACING = 1.sp
    }
}
