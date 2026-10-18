package org.deer.borrowai.data.service.summarization

import arrow.core.Either

/** Turns a transcription into a short summary. Failures are returned on the Left side of an `Either`. */
interface SummarizationService {
    suspend fun summarize(transcription: String): Either<SummarizationError, String>
}

/** Failures of [SummarizationService]. */
sealed interface SummarizationError {
    /** The summarization model has not been downloaded or is not ready. */
    data object ModelNotReady : SummarizationError

    /** No summarization engine is available on this platform build. */
    data object EngineUnavailable : SummarizationError

    /** The engine failed while summarizing. */
    data class Engine(val cause: Throwable) : SummarizationError
}

/** Placeholder binding until a summarization engine is wired. Always returns a fixed summary. */
object UnavailableSummarizationService : SummarizationService {
    const val TEXT = "Summarization unavailable"

    override suspend fun summarize(transcription: String): Either<SummarizationError, String> =
        Either.Right(TEXT)
}
