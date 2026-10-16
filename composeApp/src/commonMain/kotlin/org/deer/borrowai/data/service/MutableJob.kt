package org.deer.borrowai.data.service

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds at most one coroutine job. [launch] replaces any active job, and [cancel] cancels it and clears the reference.
 *
 * The swap is atomic: the new job is published before it starts, and the previous job is cancelled by the same
 * exchange, so no job can be left running without being tracked.
 */
@OptIn(ExperimentalAtomicApi::class)
class MutableJob(private val scope: CoroutineScope) {
    private val job = AtomicReference<Job?>(null)

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        val next = scope.launch(start = CoroutineStart.LAZY, block = block)
        job.exchange(next)?.cancel()
        next.start()
    }

    fun isActive(): Boolean = job.load()?.isActive == true

    suspend fun join() {
        job.load()?.join()
    }

    fun cancel() {
        job.exchange(null)?.cancel()
    }
}
