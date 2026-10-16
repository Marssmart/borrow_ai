package org.deer.borrowai.data.service

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
}

sealed interface PlaybackRequestResult {
    data object Started : PlaybackRequestResult

    data class ConfirmationRequired(
        val target: PlaybackTarget,
    ) : PlaybackRequestResult

    data object MemoryNotFound : PlaybackRequestResult

    /** The memory has no raw recording, or its file is missing or cannot be played on this device. */
    data object RecordingUnavailable : PlaybackRequestResult

    data object NoPendingConfirmation : PlaybackRequestResult
}

interface MediaSessionService {
    val state: StateFlow<MediaSessionState>

    val idleTranscription: StateFlow<String>

    /** False when this service cannot play back recorded media. Playback controls should be disabled. */
    val playbackAvailable: Boolean
        get() = true

    /** False when this service cannot start a raw recording. Recording controls should be disabled. */
    val recordingAvailable: Boolean
        get() = true

    fun startRecording(holdToRecord: Boolean = false)

    fun stopRecording(): RecordingResult?

    fun requestPlayback(target: PlaybackTarget): PlaybackRequestResult

    fun confirmPendingPlayback(): PlaybackRequestResult

    fun cancelPendingPlayback(): Boolean

    fun stopPlayback()

    /** Stops accepting work and returns after any save started before [close] has finished. */
    suspend fun awaitPendingWork() {}

    fun close()
}
