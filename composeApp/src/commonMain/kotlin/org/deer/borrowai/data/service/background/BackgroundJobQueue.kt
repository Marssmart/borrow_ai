package org.deer.borrowai.data.service.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stable names for application background work. Model file operations include the model file name. */
object BackgroundJobNames {
    const val TRANSCRIPTION_SWEEP = "transcription-sweep"
    const val TRANSCRIPTION_DRAIN = "transcription-drain"
    const val RECORDING_RECONCILIATION = "recording-reconciliation"
    const val DELETED_MEMORY_PURGE = "deleted-memory-purge"
    const val SUMMARY_RECOVERY = "memory-summary-recovery"
    const val CATEGORIZATION_RECOVERY = "memory-categorization-recovery"
    const val SPEAKER_ANALYSIS = "speaker-analysis"
    const val SPEAKER_RELINKING = "speaker-relinking"

    fun modelDownload(fileName: String): String = "model-download-$fileName"
    fun modelPartialCheck(fileName: String): String = "model-partial-check-$fileName"
}

data class BackgroundJobQueueState(
    val queued: Int = 0,
    val running: Int = 0,
)

data class BackgroundJobFailure(
    val jobName: String,
    val errorType: String,
)

class BackgroundJobQueue(
    scope: CoroutineScope,
    workerCount: Int = DEFAULT_WORKER_COUNT,
) {
    init {
        require(workerCount > 0) { "Worker count must be positive." }
    }

    private val channel = Channel<QueuedJob<*>>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val jobsByName = mutableMapOf<String, QueuedJob<*>>()
    private var closed = false
    private val mutableState = MutableStateFlow(BackgroundJobQueueState())
    private val mutableFailures = MutableStateFlow<List<BackgroundJobFailure>>(emptyList())

    val state: StateFlow<BackgroundJobQueueState> = mutableState.asStateFlow()
    val failures: StateFlow<List<BackgroundJobFailure>> = mutableFailures.asStateFlow()

    private val workers: List<Job> = List(workerCount) {
        scope.launch { workerLoop() }
    }

    /**
     * Adds work without waiting for it. Repeated triggers with the same name coalesce while queued; a trigger during
     * execution schedules one follow-up run after the active run finishes.
     */
    suspend fun enqueue(jobName: String, block: suspend () -> Unit): Boolean =
        enqueue(jobName, cancelOnClose = false, block = block)

    suspend fun enqueue(
        jobName: String,
        cancelOnClose: Boolean,
        block: suspend () -> Unit,
    ): Boolean {
        return getOrEnqueue(jobName, cancelOnClose, block) != null
    }

    /** Adds work or joins the same named run already queued or executing, then returns its result. */
    suspend fun <T> run(jobName: String, block: suspend () -> T): T =
        run(jobName, cancelOnClose = false, block = block)

    suspend fun <T> run(
        jobName: String,
        cancelOnClose: Boolean,
        block: suspend () -> T,
    ): T {
        val job = getOrEnqueue(jobName, cancelOnClose, block) ?: throw BackgroundJobQueueClosedException()
        return job.awaitResult()
    }

    /** Stops accepting new work, drains accepted work, and waits for every worker to exit. */
    suspend fun closeAndJoin() {
        mutex.withLock {
            if (!closed) {
                closed = true
                jobsByName.values.filter { it.cancelOnClose }.forEach { it.discard() }
                channel.close()
            }
        }
        workers.joinAll()
    }

    private suspend fun <T> getOrEnqueue(
        jobName: String,
        cancelOnClose: Boolean,
        block: suspend () -> T,
    ): QueuedJob<T>? = mutex.withLock {
        if (closed) return@withLock null

        val existing = jobsByName[jobName]
        if (existing != null) {
            if (existing.isRunning) existing.rerunRequested = true
            return@withLock existing.typed()
        }

        val job = QueuedJob(jobName, cancelOnClose, block)
        jobsByName[jobName] = job
        check(channel.trySend(job).isSuccess) { "Background job queue rejected work while open." }
        updateQueueState()
        job
    }

    private suspend fun workerLoop() {
        for (job in channel) {
            mutex.withLock {
                job.isRunning = true
                updateQueueState()
            }

            val failure = job.execute()
            if (failure != null) {
                mutableFailures.update { failures ->
                    (failures + BackgroundJobFailure(job.name, failure::class.simpleName ?: "Exception")).takeLast(MAX_RECORDED_FAILURES)
                }
            }

            mutex.withLock {
                if (jobsByName[job.name] === job) {
                    if (job.rerunRequested && !closed) {
                        val rerun = job.nextRun()
                        jobsByName[job.name] = rerun
                        check(channel.trySend(rerun).isSuccess) { "Background job queue rejected a follow-up while open." }
                    } else {
                        jobsByName.remove(job.name)
                    }
                }
                updateQueueState()
            }
        }
    }

    private fun updateQueueState() {
        val running = jobsByName.values.count { it.isRunning }
        mutableState.value = BackgroundJobQueueState(
            queued = jobsByName.size - running,
            running = running,
        )
    }

    private class QueuedJob<T>(
        val name: String,
        val cancelOnClose: Boolean,
        private val block: suspend () -> T,
    ) {
        private val result = CompletableDeferred<Result<T>>()
        var isRunning: Boolean = false
        var rerunRequested: Boolean = false

        suspend fun execute(): Exception? {
            if (discardedOnClose) {
                result.complete(Result.failure(BackgroundJobQueueClosedException()))
                return null
            }
            val taskJob = SupervisorJob(currentCoroutineContext()[Job])
            executionJob = taskJob
            val outcome = try {
                withContext(taskJob) { Result.success(block()) }
            } catch (exception: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    result.cancel(exception)
                    throw exception
                }
                if (discardedOnClose) {
                    Result.failure(BackgroundJobQueueClosedException())
                } else {
                    Result.failure(exception)
                }
            } catch (exception: Exception) {
                Result.failure(exception)
            } finally {
                taskJob.complete()
                executionJob = null
            }
            result.complete(outcome)
            if (discardedOnClose) return null
            return outcome.exceptionOrNull() as? Exception
        }

        suspend fun awaitResult(): T = result.await().getOrThrow()

        fun nextRun(): QueuedJob<T> = QueuedJob(name, cancelOnClose, block)

        fun discard() {
            discardedOnClose = true
            executionJob?.cancel(CancellationException("Background job queue is closing."))
        }

        private var executionJob: Job? = null
        private var discardedOnClose: Boolean = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> QueuedJob<*>.typed(): QueuedJob<T> = this as QueuedJob<T>

    companion object {
        const val DEFAULT_WORKER_COUNT = 2
        private const val MAX_RECORDED_FAILURES = 50
    }
}

class BackgroundJobQueueClosedException : IllegalStateException("Background job queue is closed.")
