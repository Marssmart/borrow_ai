package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.recording.MicrophoneDenial

internal class CaptureUiStateHolder(
    private val mediaSessionService: MediaSessionService,
) {
    val transcriptScrollState = ScrollState(0)

    /** True from a hold press until its release, even if recording has not started yet. */
    private var holdPressed = false

    val uiState = MutableCaptureUiState(mediaSessionService.state.value.toUiState())

    /** Raw recording does not depend on the transcription model being ready. */
    val canRecord: Boolean get() = mediaSessionService.recordingAvailable

    val microphoneDenial: StateFlow<MicrophoneDenial?> get() = mediaSessionService.microphoneDenial

    fun openMicrophoneSettings() = mediaSessionService.openMicrophoneSettings()

    suspend fun observeMediaSession() {
        mediaSessionService.state.collect { bindMediaSession(it) }
    }

    fun toggleRecording() {
        if (uiState.isRecording()) {
            stopRecording()
        } else {
            startRecording(holdToRecord = false)
        }
    }

    fun startHoldRecording() {
        holdPressed = true
        if (!uiState.isRecording()) {
            startRecording(holdToRecord = true)
        }
    }

    fun stopHoldRecording() {
        if (!holdPressed) return
        holdPressed = false
        if (uiState.isRecording()) {
            stopRecording()
        } else {
            // Released while the microphone permission prompt is open: cancel the pending start.
            mediaSessionService.stopRecording()
        }
    }

    private fun startRecording(holdToRecord: Boolean) {
        if (!canRecord) return
        mediaSessionService.startRecording(holdToRecord)
        bindMediaSession(mediaSessionService.state.value)
    }

    private fun stopRecording() {
        val result = mediaSessionService.stopRecording() ?: return
        uiState.update {
            it.copy(
                isRecording = false,
                isHoldRecording = false,
                elapsedSeconds = (result.elapsedMillis / MILLIS_PER_SECOND).toInt(),
                transcript = "",
            )
        }
    }

    private fun bindMediaSession(mediaSessionState: MediaSessionState) {
        uiState.update { currentUiState ->
            when (mediaSessionState) {
                is MediaSessionState.Recording -> mediaSessionState.toUiState()
                MediaSessionState.Idle, is MediaSessionState.Playing -> currentUiState.copy(
                    isRecording = false,
                    isHoldRecording = false,
                    transcript = "",
                )
            }
        }
    }

    private fun MediaSessionState.toUiState(): MutableCaptureUiState.UiState =
        when (this) {
            is MediaSessionState.Recording -> MutableCaptureUiState.UiState(
                isRecording = true,
                isHoldRecording = holdToRecord,
                elapsedSeconds = (elapsedMillis / MILLIS_PER_SECOND).toInt(),
                transcript = transcription,
            )

            MediaSessionState.Idle, is MediaSessionState.Playing -> MutableCaptureUiState.UiState()
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
