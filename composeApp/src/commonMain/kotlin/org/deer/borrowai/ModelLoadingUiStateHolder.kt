package org.deer.borrowai

import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState

internal class ModelLoadingUiStateHolder(
    private val modelSetupService: ModelSetupService,
) {
    data class UiState(
        val phase: ModelSetupPhase = ModelSetupPhase.NOT_STARTED,
        val progress: Float = 0f,
        val voiceModelName: String? = null,
        val llmModelName: String? = null,
    )

    private val mutableUiState = MutableStateFlow(modelSetupService.state.value.toUiState())

    val uiState: StateFlow<UiState> = mutableUiState.asStateFlow()

    suspend fun observeState() {
        modelSetupService.state.collect { state ->
            mutableUiState.value = state.toUiState()
        }
    }

    suspend fun prepare() {
        modelSetupService.prepare()
    }

    private fun ModelSetupState.toUiState() = UiState(
        phase = phase,
        progress = progress,
        voiceModelName = voiceModelName,
        llmModelName = llmModelName,
    )

    internal companion object {
        const val PROGRESS_ANIMATION_MILLIS = 100
        const val BULLET_TRANSITION_MILLIS = 220
        const val SETUP_STEP_COUNT = 4
        const val BULLET_FILL_ALPHA = 0.88f
        const val PANEL_FILL_ALPHA = 0.46f
        const val PANEL_BORDER_ALPHA = 0.94f
        const val BULLET_CENTER_DOT_RADIUS_FRACTION = 0.16f
        const val CHECK_START_X_FRACTION = 0.29f
        const val CHECK_START_Y_FRACTION = 0.52f
        const val CHECK_MIDDLE_X_FRACTION = 0.44f
        const val CHECK_MIDDLE_Y_FRACTION = 0.67f
        const val CHECK_END_X_FRACTION = 0.73f
        const val CHECK_END_Y_FRACTION = 0.34f

        val BULLET_SIZE = 22.dp
        val CONNECTOR_HEIGHT = 42.dp
        val CONNECTOR_WIDTH = 2.dp
        val BULLET_STROKE = 2.dp
        val CHECK_STROKE = 1.6.dp
        val ACTIVE_HALO_EXTENT = 4.dp

        val PANEL_CORNER_RADIUS = 24.dp
        val PANEL_HORIZONTAL_PADDING = 15.dp
        val PANEL_VERTICAL_PADDING = 17.dp
        val SCREEN_HORIZONTAL_PADDING = 32.dp
        val CONTENT_SPACING = 44.dp
        val BULLET_LABEL_PADDING = 14.dp
        val PANEL_BORDER_WIDTH = 1.dp
    }
}
