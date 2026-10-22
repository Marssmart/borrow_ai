package org.deer.borrowai.data.service

import arrow.core.Either
import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.domain.Memory

enum class PlaybackContent {
    TRANSCRIPTION,
    SUMMARY,
    RECORDING,
}

data class PlaybackTarget(
    val memoryId: String,
    val content: PlaybackContent,
) {
    /** Text shown for the target. Raw recordings are audio, so they have no text. */
    fun getText(memory: Memory): String = when (content) {
        PlaybackContent.TRANSCRIPTION -> memory.transcription
        PlaybackContent.SUMMARY -> memory.summary
        PlaybackContent.RECORDING -> ""
    }
}

data class RecordingResult(
    val elapsedMillis: Long,
    val transcription: String,
)

sealed interface RecordingError {
    /** The microphone could not be opened, so no recording was started. */
    data object MicrophoneUnavailable : RecordingError

    /** The recording was captured but could not be saved, so it was discarded. */
    data object SaveFailed : RecordingError
}

sealed interface MediaSessionState {
    data object Idle : MediaSessionState

    data class Recording(
        val elapsedMillis: Long,
        val transcription: String,
        val pendingPlayback: PlaybackTarget? = null,
        val holdToRecord: Boolean = false,
    ) : MediaSessionState

    data class Playing(
        val target: PlaybackTarget,
        val elapsedMillis: Long,
        val durationMillis: Long,
    ) : MediaSessionState

    /** A recording could not start or be saved. The UI shows it until [MediaSessionService.dismissRecordingError]. */
    data class Failed(
        val error: RecordingError,
    ) : MediaSessionState
}

/** A playback request that was accepted. Asking for confirmation is a normal step, not a failure. */
sealed interface PlaybackRequest {
    data object Started : PlaybackRequest

    data class ConfirmationRequired(
        val target: PlaybackTarget,
    ) : PlaybackRequest
}

sealed interface PlaybackError {
    data object MemoryNotFound : PlaybackError

    /** The memory has no raw recording, or its file is missing or cannot be played on this device. */
    data object RecordingUnavailable : PlaybackError

    data object NoPendingConfirmation : PlaybackError
}

interface MediaSessionService {
    val state: StateFlow<MediaSessionState>

    /** False when this service cannot play back recorded media. Playback controls should be disabled. */
    val playbackAvailable: Boolean
        get() = true

    /** False when this service cannot start a raw recording. Recording controls should be disabled. */
    val recordingAvailable: Boolean
        get() = true

    fun startRecording(holdToRecord: Boolean = false)

    fun stopRecording(): RecordingResult?

    fun requestPlayback(target: PlaybackTarget): Either<PlaybackError, PlaybackRequest>

    fun confirmPendingPlayback(): Either<PlaybackError, PlaybackRequest>

    fun cancelPendingPlayback(): Boolean

    fun stopPlayback()

    /** Clears a [MediaSessionState.Failed] state back to Idle after the user has seen it. */
    fun dismissRecordingError()

    /** Stops accepting work and returns after any save started before [close] has finished. */
    suspend fun awaitPendingWork() {}

    fun close()
}
