package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState

class DemoModelSetupService(
    private val stepDelayMillis: Long = DEFAULT_STEP_DELAY_MILLIS,
) : ModelSetupService {
    private val setupMutex = Mutex()
    private val mutableState = MutableStateFlow(ModelSetupState())
    private var completedSteps = 0

    override val state = mutableState.asStateFlow()

    init {
        require(stepDelayMillis >= 0L) { "The model setup delay cannot be negative." }
    }

    override suspend fun prepare() {
        setupMutex.withLock {
            if (mutableState.value.phase == ModelSetupPhase.READY) return

            for (step in completedSteps until SETUP_PROGRESS_STEPS) {
                val progress = (step + 1).toFloat() / SETUP_PROGRESS_STEPS
                mutableState.value = ModelSetupState(
                    phase = SETUP_PHASES[
                        ((step + 1) * SETUP_PHASES.size - 1) / SETUP_PROGRESS_STEPS
                    ],
                    progress = progress,
                )
                if (stepDelayMillis > 0L) delay(stepDelayMillis)
                completedSteps = step + 1
            }
            mutableState.value = ModelSetupState(ModelSetupPhase.READY, 1f)
        }
    }

    private companion object {
        const val DEFAULT_STEP_DELAY_MILLIS = 100L
        const val SETUP_PROGRESS_STEPS = 50
        val SETUP_PHASES = listOf(
            ModelSetupPhase.DOWNLOADING_TRANSCRIPTION,
            ModelSetupPhase.LOADING_TRANSCRIPTION,
            ModelSetupPhase.DOWNLOADING_SUMMARIZATION,
            ModelSetupPhase.LOADING_SUMMARIZATION,
        )
    }
}
