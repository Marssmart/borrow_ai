package org.deer.borrowai.data.service.recording

/** Captures microphone audio into a single file. Platform implementations write AAC audio in an M4A container. */
interface AudioRecorder {
    /** Begins capture into [absolutePath]. Throws [RecordingUnavailableException] if capture cannot start. */
    fun start(absolutePath: String)

    /** Finalizes the file started by [start]. Throws [RecordingUnavailableException] if the file cannot be finalized. */
    fun stop()
}

class RecordingUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Requests microphone access. Returns true only when the user has granted it. */
interface MicrophonePermission {
    suspend fun request(): Boolean
}

internal const val RECORDINGS_DIRECTORY = "recordings"
internal const val RECORDING_EXTENSION = "m4a"

/** Relative path stored on a memory. Resolved against the app-owned recordings root on each device. */
fun recordingRelativePath(recordingId: String): String =
    "$RECORDINGS_DIRECTORY/$recordingId.$RECORDING_EXTENSION"
