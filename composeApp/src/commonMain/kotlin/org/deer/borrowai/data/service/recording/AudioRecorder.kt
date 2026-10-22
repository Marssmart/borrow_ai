package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Captures microphone audio into a single file. Platform implementations write AAC audio in an M4A container. */
interface AudioRecorder {
    /**
     * Begins capture into [absolutePath]. Throws [RecordingUnavailableException] if capture cannot start, and
     * [java.io.IOException] if the output file cannot be created.
     */
    fun start(absolutePath: String)

    /** Finalizes the file started by [start]. Throws [RecordingUnavailableException] if the file cannot be finalized. */
    fun stop()
}

class RecordingUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Why microphone access was denied. Null means access is granted or has not been denied. */
enum class MicrophoneDenial {
    /** The user denied access once. Explaining why the app needs it can change their answer. */
    RATIONALE_REQUIRED,

    /** The user denied access for good. Only the system Settings screen can grant it. */
    PERMANENTLY_DENIED,
}

/** Classifies a microphone permission result. [showRationale] is only meaningful after a denial. */
internal fun microphoneDenialAfter(granted: Boolean, showRationale: Boolean): MicrophoneDenial? = when {
    granted -> null
    showRationale -> MicrophoneDenial.RATIONALE_REQUIRED
    else -> MicrophoneDenial.PERMANENTLY_DENIED
}

/** Requests microphone access. Returns true only when the user has granted it. */
interface MicrophonePermission {
    suspend fun request(): Boolean

    /** The current denial, or null when access is granted. Platforms without denial tracking report null. */
    val denial: StateFlow<MicrophoneDenial?>
        get() = NoMicrophoneDenial

    /** Opens the system screen where the user can grant microphone access. */
    fun openSettings() {}
}

internal val NoMicrophoneDenial: StateFlow<MicrophoneDenial?> = MutableStateFlow(null)

internal const val RECORDINGS_DIRECTORY = "recordings"
internal const val RECORDING_EXTENSION = "m4a"

/** Relative path stored on a memory. Resolved against the app-owned recordings root on each device. */
fun recordingRelativePath(recordingId: String): String =
    "$RECORDINGS_DIRECTORY/$recordingId.$RECORDING_EXTENSION"

/**
 * Relative path a capture is written to while it is in progress. It is promoted to [recordingRelativePath] only
 * after the recorder finalizes the file, so an unfinished file never looks like a finished recording.
 */
internal fun temporaryRecordingRelativePath(recordingId: String): String =
    "$RECORDINGS_DIRECTORY/$TEMPORARY_RECORDING_PREFIX$recordingId.$RECORDING_EXTENSION"

internal const val TEMPORARY_RECORDING_PREFIX = "temp_"
