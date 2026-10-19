package org.deer.borrowai.data.service.summary

/** Produces a summary from a memory's transcription. */
interface MemorySummarizer {
    suspend fun summarize(transcription: String): SummaryResult
}

sealed interface SummaryResult {
    /** A non-blank summary was produced. */
    data class Summary(val text: String) : SummaryResult

    /** The model ran but produced nothing useful (for example, the recording was too short). Counted and retried. */
    data object Empty : SummaryResult

    /** No summarizer engine is available on this build. The attempt is not counted. */
    data object Unavailable : SummaryResult

    /** The summarizer failed or timed out. The attempt is counted and the cause is stored as the last error. */
    data class Failed(val cause: Throwable) : SummaryResult
}

/** Thrown when a summarization attempt runs past its time limit. */
class SummarizationTimeoutException(timeoutMillis: Long) :
    Exception("Summarization timed out after $timeoutMillis ms")

/** Formats a failure as `"<exception class>: <message>"` for storage in the last error. */
fun Throwable.describeForStorage(): String = "${this::class.simpleName}: ${message}"

/** Summarizer used until a local LLM runtime is wired in. */
object UnavailableMemorySummarizer : MemorySummarizer {
    override suspend fun summarize(transcription: String): SummaryResult = SummaryResult.Unavailable
}
