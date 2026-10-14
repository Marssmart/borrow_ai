package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState

class DemoModelSetupService : ModelSetupService {
    private val setupMutex = Mutex()
    private val mutableState = MutableStateFlow(ModelSetupState())

    override val state = mutableState.asStateFlow()

    override suspend fun prepare() {
        setupMutex.withLock {
            if (mutableState.value.phase == ModelSetupPhase.READY) return

            mutableState.value = ModelSetupState(phase = ModelSetupPhase.CHOOSING_MODEL)
            delay(CHOOSING_DELAY_MILLIS)

            updateDownloadProgress(
                phase = ModelSetupPhase.DOWNLOADING_VOICE,
                bytesTotal = VOICE_MODEL_BYTES,
            )
            mutableState.value = ModelSetupState(
                phase = ModelSetupPhase.LOADING_VOICE,
                progress = VOICE_DOWNLOAD_END,
            )
            delay(LOADING_DELAY_MILLIS)

            updateDownloadProgress(
                phase = ModelSetupPhase.DOWNLOADING_LLM,
                bytesTotal = LLM_MODEL_BYTES,
                progressStart = VOICE_DOWNLOAD_END,
                progressEnd = 1f,
            )
            mutableState.value = ModelSetupState(
                phase = ModelSetupPhase.READY,
                progress = 1f,
            )
        }
    }

    override suspend fun downloadOverMobileData() = Unit

    private suspend fun updateDownloadProgress(
        phase: ModelSetupPhase,
        bytesTotal: Long,
        progressStart: Float = 0f,
        progressEnd: Float = VOICE_DOWNLOAD_END,
    ) {
        repeat(DOWNLOAD_STEPS) { step ->
            delay(DOWNLOAD_STEP_DELAY_MILLIS)
            val bytesDownloaded = bytesTotal * (step + 1) / DOWNLOAD_STEPS
            mutableState.value = ModelSetupState(
                phase = phase,
                progress = progressStart +
                    (progressEnd - progressStart) * (step + 1) / DOWNLOAD_STEPS,
                bytesDownloaded = bytesDownloaded,
                bytesTotal = bytesTotal,
            )
        }
    }

    private companion object {
        const val DOWNLOAD_STEPS = 15
        const val CHOOSING_DELAY_MILLIS = 300L
        const val LOADING_DELAY_MILLIS = 500L
        const val DOWNLOAD_STEP_DELAY_MILLIS = 100L
        const val VOICE_DOWNLOAD_END = 0.5f
        const val VOICE_MODEL_BYTES = 4_534_000_000L
        const val LLM_MODEL_BYTES = 5_761_000_000L
    }
}
