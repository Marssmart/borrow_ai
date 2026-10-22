package org.deer.borrowai.data.service.recording

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptionWorkStepTest {

    @Test
    fun finishedAttachedRunSucceeds() {
        assertEquals(
            TranscriptionWorkStep.SUCCEEDED,
            transcriptionWorkStep(runnerAttached = true, finished = true, runAttemptCount = 0),
        )
    }

    @Test
    fun unfinishedAttachedRunRetriesBelowCap() {
        for (attempt in 0 until MAX_TRANSCRIPTION_RUN_ATTEMPTS - 1) {
            assertEquals(
                TranscriptionWorkStep.RETRY,
                transcriptionWorkStep(runnerAttached = true, finished = false, runAttemptCount = attempt),
            )
        }
    }

    @Test
    fun unfinishedAttachedRunFailsAtCap() {
        assertEquals(
            TranscriptionWorkStep.FAILED,
            transcriptionWorkStep(
                runnerAttached = true,
                finished = false,
                runAttemptCount = MAX_TRANSCRIPTION_RUN_ATTEMPTS - 1,
            ),
        )
    }

    @Test
    fun unattachedRunRetriesBelowShorterCap() {
        for (attempt in 0 until MAX_UNATTACHED_RUN_ATTEMPTS - 1) {
            assertEquals(
                TranscriptionWorkStep.RETRY,
                transcriptionWorkStep(runnerAttached = false, finished = false, runAttemptCount = attempt),
            )
        }
    }

    @Test
    fun unattachedRunFailsAfterGracePeriod() {
        assertEquals(
            TranscriptionWorkStep.FAILED,
            transcriptionWorkStep(
                runnerAttached = false,
                finished = false,
                runAttemptCount = MAX_UNATTACHED_RUN_ATTEMPTS - 1,
            ),
        )
    }

    @Test
    fun capIsReachedForLargeAttemptCounts() {
        assertEquals(
            TranscriptionWorkStep.FAILED,
            transcriptionWorkStep(runnerAttached = true, finished = false, runAttemptCount = 100),
        )
    }
}
