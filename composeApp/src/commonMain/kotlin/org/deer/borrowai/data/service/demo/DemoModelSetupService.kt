package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState

/** Demo setup is immediately ready so recording remains available without simulated waits. */
class DemoModelSetupService : ModelSetupService {
    private val setupMutex = Mutex()
    private val mutableState = MutableStateFlow(ModelSetupState())

    override val state = mutableState.asStateFlow()

    override suspend fun prepare() {
        setupMutex.withLock {
            if (mutableState.value.phase == ModelSetupPhase.READY) return
            mutableState.value = ModelSetupState(
                phase = ModelSetupPhase.READY,
                progress = 1f,
            )
        }
    }
}
