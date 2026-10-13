package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.StateFlow

enum class ModelSetupPhase {
    NOT_STARTED,
    CHOOSING_MODEL,
    WAITING_FOR_UNMETERED_NETWORK,
    WAITING_FOR_NETWORK,
    WAITING_FOR_RETRY,
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

/** Model names are set after tier selection; byte counts are set only during downloads. */
data class ModelSetupState(
    val phase: ModelSetupPhase = ModelSetupPhase.NOT_STARTED,
    val progress: Float = 0f,
    val voiceModelName: String? = null,
    val llmModelName: String? = null,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val retryableFailure: Boolean = false,
    val failureReason: ModelSetupFailureReason? = null,
)

interface ModelSetupService {
    val state: StateFlow<ModelSetupState>

    suspend fun prepare()

    /** Allows metered networking for the currently waiting Android model download only. */
    suspend fun downloadOverMobileData() = Unit
}
