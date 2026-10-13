package org.deer.borrowai

import kotlin.test.Test
import kotlin.test.assertEquals
import org.deer.borrowai.data.service.ModelSetupPhase

class ModelDownloadWorkerTest {
    private val nowMillis = 1_000_000L

    @Test
    fun initialEnqueueStateReflectsItsNetworkConstraint() {
        assertEquals(
            ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK,
            waitingModelSetupPhase(
                runAttemptCount = 0,
                nextScheduleTimeMillis = nowMillis + 10_000L,
                nowMillis = nowMillis,
                allowMetered = false,
            ),
        )
        assertEquals(
            ModelSetupPhase.WAITING_FOR_NETWORK,
            waitingModelSetupPhase(
                runAttemptCount = 0,
                nextScheduleTimeMillis = nowMillis + 10_000L,
                nowMillis = nowMillis,
                allowMetered = true,
            ),
        )
    }

    @Test
    fun futureRetryScheduleShowsBackoffWithoutMeteredOverride() {
        assertEquals(
            ModelSetupPhase.WAITING_FOR_RETRY,
            waitingModelSetupPhase(
                runAttemptCount = 1,
                nextScheduleTimeMillis = nowMillis + 10_000L,
                nowMillis = nowMillis,
                allowMetered = false,
            ),
        )
        assertEquals(
            ModelSetupPhase.WAITING_FOR_RETRY,
            waitingModelSetupPhase(
                runAttemptCount = 1,
                nextScheduleTimeMillis = nowMillis + 10_000L,
                nowMillis = nowMillis,
                allowMetered = true,
            ),
        )
    }

    @Test
    fun expiredRetryScheduleReturnsToItsNetworkConstraintState() {
        assertEquals(
            ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK,
            waitingModelSetupPhase(
                runAttemptCount = 1,
                nextScheduleTimeMillis = nowMillis,
                nowMillis = nowMillis,
                allowMetered = false,
            ),
        )
        assertEquals(
            ModelSetupPhase.WAITING_FOR_NETWORK,
            waitingModelSetupPhase(
                runAttemptCount = 1,
                nextScheduleTimeMillis = nowMillis - 1L,
                nowMillis = nowMillis,
                allowMetered = true,
            ),
        )
    }

    @Test
    fun unscheduledWorkDoesNotAppearToBeInBackoff() {
        assertEquals(
            ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK,
            waitingModelSetupPhase(
                runAttemptCount = 1,
                nextScheduleTimeMillis = Long.MAX_VALUE,
                nowMillis = nowMillis,
                allowMetered = false,
            ),
        )
    }
}
