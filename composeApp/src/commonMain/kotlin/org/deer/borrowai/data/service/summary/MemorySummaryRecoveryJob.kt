package org.deer.borrowai.data.service.summary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.TRANSCRIPTION_PENDING_ERROR
import org.deer.borrowai.data.service.MemoryService

/**
 * Summarizes memories that were saved without a summary. Each memory is tried at most
 * [MAX_SUMMARY_ATTEMPTS] times. An attempt counts only after summarization has finished with a
 * failure. An empty result or a memory with nothing to summarize from gives up at once.
 * Runs at launch and then periodically.
 */
class MemorySummaryRecoveryJob(
    private val memoryService: MemoryService,
    private val summarizer: MemorySummarizer,
    private val isRecording: () -> Boolean,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    /** Processes eligible memories once. Returns how many summaries were stored. */
    suspend fun recover(): Int {
        if (isRecording()) return 0
        val memories = memoryService.findAll().fold(
            ifLeft = { return 0 },
            ifRight = { it },
        )
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
        }
        return stored
    }

    private fun isEligible(memory: Memory): Boolean = memory.isSummaryRetryable

    /** Returns true only when a summary was stored. */
    private suspend fun process(memory: Memory): Boolean {
        if (memory.transcription.isBlank()) {
            return when {
                // Not transcribed yet: a retry, counted, until the transcriber finishes or the limit is reached.
                !memory.transcriptionDone && memory.recordingPath != null -> {
                    memoryService.recordFinishedAttempt(memory.id, TRANSCRIPTION_PENDING_ERROR)
                    false
                }
                // Transcribed and blank, or no recording to transcribe: nothing will change, so give up now.
                else -> {
                    memoryService.giveUpSummary(memory.id)
                    false
                }
            }
        }
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
