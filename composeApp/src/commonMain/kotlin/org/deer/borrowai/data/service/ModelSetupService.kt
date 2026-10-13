package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.StateFlow

enum class ModelSetupPhase {
    NOT_STARTED,
    CHOOSING_MODEL,
    WAITING_FOR_UNMETERED_NETWORK,
    DOWNLOADING_VOICE,
    LOADING_VOICE,
    DOWNLOADING_LLM,
    READY,
    FAILED,
}

enum class ModelSetupFailureReason {
    NETWORK,
    STORAGE,
    MODEL,
}

/** Names are null until the device tier has been chosen. [failedDuring] keeps the step that failed visible. Byte counts are set only while a model downloads. */
data class ModelSetupState(
    val phase: ModelSetupPhase = ModelSetupPhase.NOT_STARTED,
    val progress: Float = 0f,
    val voiceModelName: String? = null,
    val llmModelName: String? = null,
    val failedDuring: ModelSetupPhase? = null,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val animateTransitions: Boolean = true,
    val retryableFailure: Boolean = false,
    val failureReason: ModelSetupFailureReason? = null,
)

interface ModelSetupService {
    val state: StateFlow<ModelSetupState>

    suspend fun prepare()
}
