package org.deer.borrowai.data.service.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundJobQueueTest {
    @Test
    fun fixedWorkersExecuteJobsInFifoOrder() = runTest {
        val queue = BackgroundJobQueue(backgroundScope, workerCount = 2)
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        var active = 0
        var maximumActive = 0

        repeat(3) { id ->
            queue.enqueue("job.$id") {
                active++
                maximumActive = maxOf(maximumActive, active)
                started += id
                release.await()
                active--
            }
        }
        runCurrent()

        assertEquals(listOf(0, 1), started)
        assertEquals(2, maximumActive)
        assertEquals(BackgroundJobQueueState(queued = 1, running = 2), queue.state.value)

        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(0, 1, 2), started)
        assertEquals(0, active)
        assertEquals(0, queue.state.value.running)
        queue.closeAndJoin()
        runCurrent()
    }

    @Test
    fun duplicateQueuedJobsCoalesceAndTriggersDuringExecutionScheduleOneRerun() = runTest {
        val queue = BackgroundJobQueue(backgroundScope, workerCount = 1)
        val release = CompletableDeferred<Unit>()
        var runs = 0

        queue.enqueue("same.job") {
            runs++
            release.await()
        }
        queue.enqueue("same.job") { runs += 100 }
        runCurrent()
        assertEquals(1, runs)

        queue.enqueue("same.job") { runs += 100 }
        release.complete(Unit)
        runCurrent()

        assertEquals(2, runs)
        queue.closeAndJoin()
        runCurrent()
    }

    @Test
    fun closeStopsNewSubmissionsAndDrainsAcceptedJobs() = runTest {
        val queue = BackgroundJobQueue(backgroundScope, workerCount = 1)
        val release = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        var completed = 0
        var closed = false

        queue.enqueue("first") {
            firstStarted.complete(Unit)
            release.await()
            completed++
        }
        queue.enqueue("second") { completed++ }
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        val closing = backgroundScope.launch {
            queue.closeAndJoin()
            closed = true
        }
        runCurrent()
        assertFalse(closed)
        assertFalse(queue.enqueue("late") { completed++ })

        release.complete(Unit)
        runCurrent()

        assertTrue(closed)
        assertEquals(2, completed)
        closing.join()
    }

    @Test
    fun closeCancelsModelTransfersButDrainsDatabaseJobs() = runTest {
        val queue = BackgroundJobQueue(backgroundScope, workerCount = 1)
        val transferStarted = CompletableDeferred<Unit>()
        val transferCancelled = CompletableDeferred<Unit>()
        var databaseWorkCompleted = false

        queue.enqueue(
            "model-download",
            cancelOnClose = true,
        ) {
        transferStarted.complete(Unit)
        try {
            CompletableDeferred<Unit>().await()
        } finally {
            transferCancelled.complete(Unit)
        }
        }
        queue.enqueue("database-recovery") { databaseWorkCompleted = true }
        runCurrent()
        assertTrue(transferStarted.isCompleted)

        queue.closeAndJoin()
        runCurrent()

        assertTrue(transferCancelled.isCompleted)
        assertTrue(databaseWorkCompleted)
    }

    @Test
    fun failedJobsAreReportedAndDoNotStopWorkers() = runTest {
        val queue = BackgroundJobQueue(backgroundScope, workerCount = 1)
        var laterJobCompleted = false

        queue.enqueue("failed-job") { error("failure") }
        queue.enqueue("later-job") { laterJobCompleted = true }
        runCurrent()

        assertEquals(
            listOf(BackgroundJobFailure("failed-job", "IllegalStateException")),
            queue.failures.value,
        )
        assertTrue(laterJobCompleted)
        queue.closeAndJoin()
        runCurrent()
    }
}
