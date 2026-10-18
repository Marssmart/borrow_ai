package org.deer.borrowai.data.service

import arrow.core.Either
import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.service.model.ModelDownloadError

enum class ModelSetupPhase {
    NOT_STARTED,
    CHOOSING_MODEL,
    WAITING_FOR_UNMETERED_NETWORK,
    WAITING_FOR_NETWORK,
    DOWNLOADING_LIVE_VOICE,
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
    /** Developer-facing detail of the failure, for logs and diagnostics. Not shown to users. */
    val failureDetail: String? = null,
) {
    val isReady: Boolean
        get() = phase == ModelSetupPhase.READY
}

interface ModelSetupService {
    val state: StateFlow<ModelSetupState>

    suspend fun prepare(): Either<ModelDownloadError, Unit>

    suspend fun downloadOverMobileData()
}
