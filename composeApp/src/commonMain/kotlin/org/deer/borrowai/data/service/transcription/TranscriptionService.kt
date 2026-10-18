package org.deer.borrowai.data.service.transcription

import arrow.core.Either

/** Turns a saved recording into text. Failures are returned on the Left side of an `Either`. */
interface TranscriptionService {
    /** [relativePath] is the recording path relative to the recordings root. */
    suspend fun transcribe(relativePath: String): Either<TranscriptionError, String>
}

/** Failures of [TranscriptionService]. */
sealed interface TranscriptionError {
    /** The speech-to-text model has not been downloaded or is not ready. */
    data object ModelNotReady : TranscriptionError

    /** No speech-to-text engine is available on this platform build. */
    data object EngineUnavailable : TranscriptionError

    /** The recording could not be read or decoded. */
    data class InvalidAudio(val relativePath: String) : TranscriptionError

    /** The engine failed while transcribing. */
    data class Engine(val cause: Throwable) : TranscriptionError
}

/** Placeholder binding until a platform speech-to-text engine is wired. Always returns a fixed transcript. */
object UnavailableTranscriptionService : TranscriptionService {
    const val TEXT = "Transcription unavailable"

    override suspend fun transcribe(relativePath: String): Either<TranscriptionError, String> =
        Either.Right(TEXT)
}
