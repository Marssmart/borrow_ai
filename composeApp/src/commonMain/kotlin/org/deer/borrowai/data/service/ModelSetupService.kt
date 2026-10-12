package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.StateFlow

enum class ModelSetupPhase {
    NOT_STARTED,
    DOWNLOADING_TRANSCRIPTION,
    LOADING_TRANSCRIPTION,
    DOWNLOADING_SUMMARIZATION,
    LOADING_SUMMARIZATION,
    READY,
}

data class ModelSetupState(
    val phase: ModelSetupPhase = ModelSetupPhase.NOT_STARTED,
    val progress: Float = 0f,
)

interface ModelSetupService {
    val state: StateFlow<ModelSetupState>

    suspend fun prepare()
}
