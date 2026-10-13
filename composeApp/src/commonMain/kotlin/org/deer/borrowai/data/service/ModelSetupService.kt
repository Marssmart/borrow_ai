package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.StateFlow

enum class ModelSetupPhase {
    NOT_STARTED,
    CHOOSING_MODEL,
    DOWNLOADING_VOICE,
    LOADING_VOICE,
    DOWNLOADING_LLM,
    READY,
    FAILED,
}

/** Names are null until the device tier has been chosen. [failedDuring] keeps the step that failed visible. */
data class ModelSetupState(
    val phase: ModelSetupPhase = ModelSetupPhase.NOT_STARTED,
    val progress: Float = 0f,
    val voiceModelName: String? = null,
    val llmModelName: String? = null,
    val failedDuring: ModelSetupPhase? = null,
)

interface ModelSetupService {
    val state: StateFlow<ModelSetupState>

    suspend fun prepare()
}
