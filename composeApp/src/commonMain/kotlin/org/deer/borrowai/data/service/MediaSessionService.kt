package org.deer.borrowai.data.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.recording.LiveTranscriptionFailure
import org.deer.borrowai.data.service.recording.MicrophoneDenial
import org.deer.borrowai.data.service.recording.NoMicrophoneDenial

enum class PlaybackContent {
    TRANSCRIPTION,
    SUMMARY,
    RECORDING,

    /** The stretch of a recording where one speaker talks. The target's [PlaybackTarget.sample] gives its range. */
    SPEAKER_SAMPLE,
}

/** A range of a recording in milliseconds from its start. [endMs] is after [startMs]. */
data class PlaybackSample(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0 && endMs > startMs) { "A sample needs a positive time range." }
    }
}

data class PlaybackTarget(
    val memoryId: String,
    val content: PlaybackContent,
    /** The range played for [PlaybackContent.SPEAKER_SAMPLE]. Only that content has a range. */
    val sample: PlaybackSample? = null,
) {
    init {
        require((content == PlaybackContent.SPEAKER_SAMPLE) == (sample != null)) {
            "Only a speaker sample target has a sample range."
        }
    }

    /** Text shown for the target. Raw recordings and speaker samples are audio, so they have no text. */
    fun getText(memory: Memory): String = when (content) {
        PlaybackContent.TRANSCRIPTION -> memory.transcription
        PlaybackContent.SUMMARY -> memory.summary
        PlaybackContent.RECORDING, PlaybackContent.SPEAKER_SAMPLE -> ""
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
        val liveTranscriptionFailure: LiveTranscriptionFailure? = null,
    ) : MediaSessionState

    data class Playing(
        val target: PlaybackTarget,
        val elapsedMillis: Long,
        val durationMillis: Long,
        val paused: Boolean = false,
        /** True when another app took the audio focus and paused playback. The paused notice shows it. */
        val pausedByOtherApp: Boolean = false,
        /** The playback speed of this memory, which is the session speed. */
        val speed: Float = 1f,
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

    /** Paused playback could not resume for a reason other than the audio focus. It stays paused. */
    data object ResumeFailed : PlaybackError

    /**
     * Paused playback could not resume because another app holds the audio focus. It stays paused, with the notice.
     */
    data object AudioFocusHeld : PlaybackError
}

interface MediaSessionService {
    val state: StateFlow<MediaSessionState>

    /** False when this service cannot play back recorded media. Playback controls should be disabled. */
    val playbackAvailable: Boolean
        get() = true

    /**
     * False when this service cannot pause, resume, or seek playback. The playback button then only plays and stops,
     * as it did before playback controls existed.
     */
    val playbackControlsAvailable: Boolean
        get() = false

    /** False when this service cannot start a raw recording. Recording controls should be disabled. */
    val recordingAvailable: Boolean
        get() = true

    /** True when [memory] has its raw recording on disk, so the recording and its speaker samples can be played. */
    fun hasRecordingFile(memory: Memory): Boolean = false

    /** Why microphone access was denied, or null when it is granted. Platforms without denial tracking report null. */
    val microphoneDenial: StateFlow<MicrophoneDenial?>
        get() = NoMicrophoneDenial

    /** True while a finished recording is being saved as a memory. The empty memory list waits for it, so a new memory does not flash. */
    val saving: Flow<Boolean>
        get() = flowOf(false)

    /** Opens the system screen where the user can grant microphone access. */
    fun openMicrophoneSettings() {}

    fun startRecording(holdToRecord: Boolean = false)

    fun stopRecording(): RecordingResult?

    fun requestPlayback(target: PlaybackTarget): Either<PlaybackError, PlaybackRequest>

    fun confirmPendingPlayback(): Either<PlaybackError, PlaybackRequest>

    fun cancelPendingPlayback(): Boolean

    fun stopPlayback()

    /** Pauses the active playback and keeps its position. */
    fun pausePlayback() {}

    /** Resumes paused playback from its position. Playback paused at its end starts again from the beginning. */
    fun resumePlayback(): Either<PlaybackError, Unit> = PlaybackError.RecordingUnavailable.left()

    /** Moves the playback position to [positionMillis], clamped to the start and the end of the recording. */
    fun seekPlayback(positionMillis: Long) {}

    /** Moves the playback position by [deltaMillis], clamped to the start and the end of the recording. */
    fun skipPlayback(deltaMillis: Long) {}

    /**
     * The playback speed for every memory in this app session. It starts at 1x. Platforms without speed control
     * keep it at 1x.
     */
    val playbackSpeed: StateFlow<Float>
        get() = DefaultPlaybackSpeed

    /** Sets the playback speed. It applies at once to the playing memory and to every memory played after it. */
    fun setPlaybackSpeed(speed: Float) {}

    /** Clears a [MediaSessionState.Failed] state back to Idle after the user has seen it. */
    fun dismissRecordingError()

    /** Stops accepting work and returns after any save started before [close] has finished. */
    suspend fun awaitPendingWork() {}

    fun close()
}

internal val DefaultPlaybackSpeed: StateFlow<Float> = MutableStateFlow(1f)
