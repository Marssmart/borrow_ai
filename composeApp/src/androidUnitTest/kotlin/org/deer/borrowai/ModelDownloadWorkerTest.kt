package org.deer.borrowai

import kotlin.test.Test
import kotlin.test.assertEquals
import org.deer.borrowai.data.service.ModelSetupPhase

class ModelDownloadWorkerTest {
    @Test
    fun initialEnqueueStateReflectsItsNetworkConstraint() {
        assertEquals(
            ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK,
            waitingModelSetupPhase(runAttemptCount = 0, allowMetered = false),
        )
        assertEquals(
            ModelSetupPhase.WAITING_FOR_NETWORK,
            waitingModelSetupPhase(runAttemptCount = 0, allowMetered = true),
        )
    }

    @Test
    fun retryBackoffDoesNotOfferMeteredOverride() {
        assertEquals(
            ModelSetupPhase.WAITING_FOR_RETRY,
            waitingModelSetupPhase(runAttemptCount = 1, allowMetered = false),
        )
        assertEquals(
            ModelSetupPhase.WAITING_FOR_RETRY,
            waitingModelSetupPhase(runAttemptCount = 1, allowMetered = true),
        )
    }
}
