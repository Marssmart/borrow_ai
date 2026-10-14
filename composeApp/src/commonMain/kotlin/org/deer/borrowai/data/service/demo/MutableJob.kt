package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Holds at most one coroutine job. [launch] replaces any active job, and [cancel] cancels it and clears the reference.
 */
class MutableJob(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        cancel()
        job = scope.launch(block = block)
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
