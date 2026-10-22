package org.deer.borrowai.data.service.summary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.MemoryService

/**
 * Summarizes memories that were saved without a summary. Each memory is tried at most
 * [MAX_SUMMARY_ATTEMPTS] times. An attempt counts only after summarization has finished with a
 * failure. A memory with no transcript is skipped, not attempted or counted. An empty result gives up at once.
 * Runs at launch and then periodically.
 */
class MemorySummaryRecoveryJob(
    private val memoryService: MemoryService,
    private val summarizer: MemorySummarizer,
    private val isRecording: () -> Boolean,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val modelMissing = MutableStateFlow(false)

    /** True when the last [recover] run stopped because the summary model file is not on the device. */
    val summaryModelMissing: StateFlow<Boolean> = modelMissing.asStateFlow()

    /** Processes eligible memories once. Returns how many summaries were stored. */
    suspend fun recover(): Int {
        if (isRecording()) return 0
        val memories = memoryService.findAll().fold(
            ifLeft = { return 0 },
            ifRight = { it },
        )
        modelMissing.value = false
        var stored = 0
        for (memory in memories.filter(::isEligible)) {
            if (isRecording()) break
            try {
                if (process(memory)) stored++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // The memory keeps its count and is retried on the next run.
            }
            // A missing model will be missing for every memory, so stop instead of repeating the same result.
            if (modelMissing.value) break
        }
        return stored
    }

    private fun isEligible(memory: Memory): Boolean = memory.isSummaryRetryable

    /** Returns true only when a summary was stored. */
    private suspend fun process(memory: Memory): Boolean {
        // No transcript means nothing to summarize: skip without calling the summarizer or counting an attempt.
        if (memory.transcription.isBlank()) return false
        return when (val result = summarize(memory.transcription)) {
            is SummaryResult.Summary -> if (result.text.isBlank()) {
                memoryService.recordEmpty(memory.id)
                false
            } else {
                memoryService.recordSummary(memory.id, result.text).isRight()
            }
            SummaryResult.Empty -> {
                memoryService.recordEmpty(memory.id)
                false
            }
            SummaryResult.Unavailable -> false
            SummaryResult.ModelMissing -> {
                modelMissing.value = true
                false
            }
            is SummaryResult.Failed -> {
                memoryService.recordFinishedAttempt(memory.id, result.cause.describeForStorage())
                false
            }
        }
    }

    private suspend fun summarize(transcription: String): SummaryResult = try {
        withTimeout(timeoutMillis) { summarizer.summarize(transcription) }
    } catch (exception: TimeoutCancellationException) {
        SummaryResult.Failed(SummarizationTimeoutException(timeoutMillis))
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        SummaryResult.Failed(exception)
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
        const val DEFAULT_TIMEOUT_MILLIS = 60 * 1000L
    }
}
