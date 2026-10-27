package org.deer.borrowai.data.service.category

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.MemoryService

/**
 * Categorizes memories that have no categories yet, including memories saved before categorization existed.
 * Each run takes at most [batchSize] transcribed memories. A memory with no transcript is skipped without
 * counting an attempt. A finished categorization that yields categories is stored, even when backfill
 * persistence failed. A failed run counts one attempt, and the memory is retried until its attempts run out.
 * Runs at launch and then periodically.
 */
class MemoryCategorizationRecoveryJob(
    private val memoryService: MemoryService,
    private val categorizer: MemoryCategorizer,
    private val isRecording: () -> Boolean,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "Batch size must be positive." }
    }

    private val runMutex = Mutex()

    /**
     * Processes one batch of eligible memories. Returns how many memories received categories. Runs are serialized,
     * so overlapping triggers cannot snapshot the same memory or overwrite a stored result with a later failure.
     */
    suspend fun recover(): Int = runMutex.withLock {
        if (isRecording()) return@withLock 0
        val memories = memoryService.findAll().fold(
            ifLeft = { return@withLock 0 },
            ifRight = { it },
        )
        var stored = 0
        for (memory in memories.filter(::isEligible).take(batchSize)) {
            if (isRecording()) break
            val outcome = try {
                process(memory)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logCategorization("Unexpected error while categorizing ${memory.id}.", exception)
                Outcome.NOT_STORED
            }
            if (outcome == Outcome.STORED) stored++
            // A missing model will be missing for every memory, so stop instead of repeating the same result.
            // A failed store only affects this memory, so the rest of the batch still runs.
            if (outcome == Outcome.MODEL_MISSING) break
        }
        stored
    }

    private fun isEligible(memory: Memory): Boolean =
        memory.isCategorizationRetryable && memory.transcription.isNotBlank()

    private enum class Outcome { STORED, NOT_STORED, MODEL_MISSING, STORE_FAILED }

    private suspend fun process(memory: Memory): Outcome =
        when (val result = categorize(memory.transcription)) {
            is CategorizationResult.Categories -> {
                result.backfillFailure?.let {
                    logCategorization("Backfill failed for ${memory.id}; storing ${result.ids.size} categories.", it)
                }
                if (memoryService.recordCategorization(memory.id, result.ids).isRight()) {
                    logCategorization("Stored ${result.ids.size} categories for ${memory.id}.")
                    Outcome.STORED
                } else {
                    logCategorization("Could not store categories for ${memory.id}.")
                    Outcome.STORE_FAILED
                }
            }
            CategorizationResult.Empty -> recordAttempt(memory.id, empty = true, reason = "empty transcription")
            CategorizationResult.Unavailable -> {
                logCategorization("Categorizer unavailable on this build; skipped ${memory.id}.")
                Outcome.NOT_STORED
            }
            CategorizationResult.ModelMissing -> {
                logCategorization("Categorizer model missing; stopping the batch.")
                Outcome.MODEL_MISSING
            }
            is CategorizationResult.Failed ->
                recordAttempt(memory.id, empty = false, reason = "categorizer failed", cause = result.cause)
        }

    private suspend fun recordAttempt(
        id: String,
        empty: Boolean,
        reason: String,
        cause: Throwable? = null,
    ): Outcome {
        logCategorization("No categories for $id ($reason); recording attempt.", cause)
        return if (memoryService.recordCategorizationAttempt(id, empty).isRight()) {
            Outcome.NOT_STORED
        } else {
            logCategorization("Could not record categorization attempt for $id.")
            Outcome.STORE_FAILED
        }
    }

    private suspend fun categorize(transcription: String): CategorizationResult = try {
        withTimeout(timeoutMillis) { categorizer.categorize(transcription) }
    } catch (exception: TimeoutCancellationException) {
        CategorizationResult.Failed(IllegalStateException("Categorization timed out after $timeoutMillis ms."))
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        CategorizationResult.Failed(exception)
    }

    /** Runs [recover] now and then every [intervalMillis] while [scope] is active. */
    fun start(scope: CoroutineScope, intervalMillis: Long = DEFAULT_INTERVAL_MILLIS): Job = scope.launch {
        while (isActive) {
            recover()
            delay(intervalMillis)
        }
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 15 * 60 * 1000L
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_BATCH_SIZE = 5
    }
}
