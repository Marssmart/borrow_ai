package org.deer.borrowai.data.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Writes started from a screen that may leave before they finish. [stopAccepting] refuses later writes, and [awaitAll]
 * then waits for the ones already started, so app close can wait for them before the database closes.
 */
internal class PendingWrites(private val scope: CoroutineScope) {
    /** Accepting and the started writes change together, so a write cannot join after [awaitAll] has checked. */
    private val state = MutableStateFlow(State())

    /** Starts [block] unless [stopAccepting] was called. A refused write never runs. */
    fun launch(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        val before = state.getAndUpdate { if (it.accepting) it.copy(jobs = it.jobs + job) else it }
        if (!before.accepting) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { state.update { it.copy(jobs = it.jobs - job) } }
        job.start()
    }

    /** Refuses writes from now on. Writes already started stay tracked for [awaitAll]. */
    fun stopAccepting() {
        state.update { it.copy(accepting = false) }
    }

    /** Waits for every started write. Call it after [stopAccepting], so the set can only shrink while it waits. */
    suspend fun awaitAll() {
        while (true) {
            val job = state.value.jobs.firstOrNull() ?: return
            job.join()
        }
    }

    private data class State(val accepting: Boolean = true, val jobs: List<Job> = emptyList())
}
