package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.StateFlow

enum class ModelSetupPhase {
    NOT_STARTED,
    CHOOSING_MODEL,
    WAITING_FOR_UNMETERED_NETWORK,
    WAITING_FOR_NETWORK,
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

data class ModelSetupState(
    val phase: ModelSetupPhase = ModelSetupPhase.NOT_STARTED,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val failureReason: ModelSetupFailureReason? = null,
)

interface ModelSetupService {
    val state: StateFlow<ModelSetupState>

    suspend fun prepare()

    suspend fun downloadOverMobileData() = Unit
}
