package org.deer.borrowai.data.service

import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class MutableJobTest {
    @Test
    fun launchReplacesAndCancelsThePreviousJob() = runTest {
        val mutableJob = MutableJob(backgroundScope)
        var firstJob: Job? = null
        var secondRuns = 0

        mutableJob.launch {
            firstJob = coroutineContext[Job]
            delay(Long.MAX_VALUE)
        }
        runCurrent()
        mutableJob.launch {
            secondRuns += 1
            delay(Long.MAX_VALUE)
        }
        runCurrent()

        assertEquals(1, secondRuns)
        assertFalse(firstJob!!.isActive)
    }

    @Test
    fun cancelStopsTheCurrentJobAndAllowsRelaunch() = runTest {
        val mutableJob = MutableJob(backgroundScope)
        var runs = 0

        mutableJob.launch {
            runs += 1
            delay(Long.MAX_VALUE)
        }
        runCurrent()
        mutableJob.cancel()
        mutableJob.cancel()

        mutableJob.launch {
            runs += 1
            delay(Long.MAX_VALUE)
        }
        runCurrent()

        assertEquals(2, runs)
    }

    @Test
    fun concurrentLaunchesLeaveExactlyOneLiveJob() = runTest {
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mutableJob = MutableJob(realScope)
        val lock = Mutex()
        val startedJobs = mutableListOf<Job>()

        coroutineScope {
            (1..200).map {
                async(Dispatchers.Default) {
                    mutableJob.launch {
                        lock.withLock { startedJobs += coroutineContext[Job]!! }
                        delay(Long.MAX_VALUE)
                    }
                }
            }.awaitAll()
        }

        suspend fun liveCount(): Int = withContext(Dispatchers.Default) {
            lock.withLock { startedJobs.count { it.isActive } }
        }

        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (liveCount() != 1) delay(1)
            }
        }
        assertEquals(1, liveCount())

        mutableJob.cancel()
        val jobs = lock.withLock { startedJobs.toList() }
        jobs.forEach { it.join() }
        assertTrue(jobs.none { it.isActive })
        realScope.cancel()
    }
}
