package org.deer.borrowai.data.service.model

import org.deer.borrowai.data.service.ModelSetupFailureReason
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.MutableModelSetupState

/**
 * Stands in for the model download pipeline until REAL model setup is wired. It reports a failure
 * instead of pretending the models are ready.
 */
class UnavailableModelSetupService : ModelSetupService {
    override val state = MutableModelSetupState()

    override suspend fun prepare() {
        state.fail(ModelSetupFailureReason.MODEL)
    }

    override suspend fun downloadOverMobileData() = Unit
}
