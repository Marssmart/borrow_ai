package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelTier
import org.deer.borrowai.data.service.model.TierModels

/** Simulates the setup timeline using the models that a real device of this tier would choose. */
class DemoModelSetupService(
    private val stepDelayMillis: Long = DEFAULT_STEP_DELAY_MILLIS,
    private val models: TierModels = ModelCatalog.modelsFor(ModelTier.MID),
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

            for (step in completedSteps until TOTAL_STEPS) {
                val phase = STAGE_PHASES[step / STEPS_PER_STAGE]
                val chosen = phase != ModelSetupPhase.CHOOSING_MODEL
                mutableState.value = ModelSetupState(
                    phase = phase,
                    progress = (step + 1).toFloat() / TOTAL_STEPS,
                    voiceModelName = if (chosen) models.voice.displayName else null,
                    llmModelName = if (chosen) models.llm.displayName else null,
                )
                if (stepDelayMillis > 0L) delay(stepDelayMillis)
                completedSteps = step + 1
            }
            mutableState.value = ModelSetupState(
                phase = ModelSetupPhase.READY,
                progress = 1f,
                voiceModelName = models.voice.displayName,
                llmModelName = models.llm.displayName,
            )
        }
    }

    private companion object {
        const val DEFAULT_STEP_DELAY_MILLIS = 100L
        const val STEPS_PER_STAGE = 12
        val STAGE_PHASES = listOf(
            ModelSetupPhase.CHOOSING_MODEL,
            ModelSetupPhase.DOWNLOADING_VOICE,
            ModelSetupPhase.LOADING_VOICE,
            ModelSetupPhase.DOWNLOADING_LLM,
        )
        val TOTAL_STEPS = STAGE_PHASES.size * STEPS_PER_STAGE
    }
}
