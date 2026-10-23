package org.deer.borrowai.data.service.summary

import org.deer.borrowai.data.service.llm.LlmOutcome
import org.deer.borrowai.data.service.llm.LocalLlmRuntime

/** Creates a [MemorySummarizer] on [runtime]. The runtime is owned and closed by the caller. */
internal fun createSummarizer(runtime: LocalLlmRuntime): MemorySummarizer = AndroidSummarizer(runtime)

/**
 * Summarizes a transcription on the shared [LocalLlmRuntime]. Returns [SummaryResult.ModelMissing] while the model
 * file is missing. Each memory gets a fresh conversation, so earlier summaries do not leak into later ones.
 */
internal class AndroidSummarizer(
    private val runtime: LocalLlmRuntime,
    private val timeoutMillis: Long = SUMMARY_TIMEOUT_MILLIS,
) : MemorySummarizer {
    override suspend fun summarize(transcription: String): SummaryResult =
        when (val outcome = runtime.generate(SummaryPrompt.SYSTEM_INSTRUCTION, SummaryPrompt.userMessage(transcription), timeoutMillis)) {
            is LlmOutcome.Text -> SummaryPrompt.parse(outcome.text)
            LlmOutcome.ModelMissing -> SummaryResult.ModelMissing
            LlmOutcome.TimedOut -> SummaryResult.Failed(SummarizationTimeoutException(timeoutMillis))
            is LlmOutcome.Failed -> SummaryResult.Failed(outcome.cause)
        }

    private companion object {
        const val SUMMARY_TIMEOUT_MILLIS = 60_000L
    }
}
