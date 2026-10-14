package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MutableMediaSessionState {
    private val mutableState = MutableStateFlow<MediaSessionState>(MediaSessionState.Idle)

    val state: StateFlow<MediaSessionState> = mutableState.asStateFlow()

    val value: MediaSessionState
        get() = mutableState.value

    fun transitionIdle() {
        mutableState.value = MediaSessionState.Idle
    }

    fun transitionRecording(
        elapsedMillis: Long = 0L,
        transcription: String = "",
        pendingPlayback: PlaybackTarget? = null,
        holdToRecord: Boolean = false,
    ) {
        mutableState.value = MediaSessionState.Recording(
            elapsedMillis = elapsedMillis,
            transcription = transcription,
            pendingPlayback = pendingPlayback,
            holdToRecord = holdToRecord,
        )
    }

    fun transitionPlaying(
        target: PlaybackTarget,
        durationMillis: Long,
        elapsedMillis: Long = 0L,
    ) {
        mutableState.value = MediaSessionState.Playing(
            target = target,
            elapsedMillis = elapsedMillis,
            durationMillis = durationMillis,
        )
    }

    /**
     * Runs [before] only if the session is recording, then reads the latest recording
     * so that updates made during [before] (e.g. cancelling jobs) are visible to [block].
     */
    fun <R> ifRecording(
        before: () -> Unit = {},
        block: (MediaSessionState.Recording) -> R,
    ): R? {
        mutableState.value as? MediaSessionState.Recording ?: return null
        before()
        val recording = mutableState.value as? MediaSessionState.Recording ?: return null
        return block(recording)
    }

    fun <R> ifPlaying(block: (MediaSessionState.Playing) -> R): R? {
        val playing = mutableState.value as? MediaSessionState.Playing ?: return null
        return block(playing)
    }

    internal fun update(transform: (MediaSessionState) -> MediaSessionState) {
        mutableState.update(transform)
    }
}
