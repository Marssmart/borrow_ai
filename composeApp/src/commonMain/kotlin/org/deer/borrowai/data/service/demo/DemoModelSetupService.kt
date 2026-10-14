package org.deer.borrowai.data.service.demo

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

/** Exposes the models selected for the emulated device tier without delaying the capture screen. */
class DemoModelSetupService(
    private val models: TierModels = ModelCatalog.modelsFor(ModelTier.MID),
) : ModelSetupService {
    private val setupMutex = Mutex()
    private val mutableState = MutableStateFlow(ModelSetupState())

    override val state = mutableState.asStateFlow()

    override suspend fun prepare() {
        setupMutex.withLock {
            mutableState.value = ModelSetupState(
                phase = ModelSetupPhase.READY,
                progress = 1f,
                voiceModelName = models.voice.displayName,
                llmModelName = models.llm.displayName,
            )
        }
    }
}
