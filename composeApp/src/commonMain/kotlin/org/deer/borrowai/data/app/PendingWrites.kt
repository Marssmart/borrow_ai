package org.deer.borrowai.data.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Writes started from a screen that may leave before they finish. [awaitAll] lets app close wait for them, so the
 * database does not close under a write that is still running.
 */
internal class PendingWrites(private val scope: CoroutineScope) {
    private val jobs = MutableStateFlow<List<Job>>(emptyList())

    /** Starts [block]. The write is tracked from the moment it is launched, so [awaitAll] cannot miss it. */
    fun launch(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.update { it + job }
        job.invokeOnCompletion { jobs.update { finished -> finished - job } }
        job.start()
    }

    /** Waits for every write that has started and has not finished yet. */
    suspend fun awaitAll() {
        while (true) {
            val job = jobs.value.firstOrNull() ?: return
            job.join()
        }
    }
}
