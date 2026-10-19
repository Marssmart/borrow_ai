package org.deer.borrowai.data.domain

/**
 * A saved note.
 *
 * [recordingPath] is the location of the raw recording relative to the app-owned recordings
 * directory (for example `recordings/abc.m4a`). It is never an absolute path: iOS changes the
 * app container path across reinstalls and updates, so the platform layer resolves the relative
 * path at playback time.
 *
 * A [deleted] memory is hidden from the UI. Its audio file and record are removed later by a purge
 * job, so the reconciler does not mistake the leftover recording for an unsaved one.
 */
data class Memory(
    val id: String,
    val createdAtEpochMillis: Long,
    val title: String,
    val summary: String,
    val transcription: String,
    val recordingPath: String? = null,
    val deleted: Boolean = false,
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.DONE,
    val transcriptionAttempts: Int = 0,
) {
    init {
        recordingPath?.let(::requireRelativeRecordingPath)
        require(transcriptionAttempts >= 0) { "Transcription attempts cannot be negative." }
    }
}

/**
 * Where a memory's transcript is in the recovery pipeline. [PENDING] means the transcript still has to be
 * generated, [IN_PROGRESS] means a worker has claimed it, [DONE] means the transcript is final, and [FAILED]
 * means it was attempted [MAX_TRANSCRIPTION_ATTEMPTS] times without success and will not be retried.
 */
enum class TranscriptionStatus { PENDING, IN_PROGRESS, DONE, FAILED }

const val MAX_TRANSCRIPTION_ATTEMPTS = 5

fun Memory.claimedForTranscription(): Memory = copy(
    transcriptionStatus = TranscriptionStatus.IN_PROGRESS,
    transcriptionAttempts = transcriptionAttempts + 1,
)

fun Memory.withFinishedTranscription(text: String): Memory = copy(
    transcription = text,
    transcriptionStatus = TranscriptionStatus.DONE,
)

fun Memory.withFailedTranscription(): Memory = copy(
    transcriptionStatus = if (transcriptionAttempts >= MAX_TRANSCRIPTION_ATTEMPTS) {
        TranscriptionStatus.FAILED
    } else {
        TranscriptionStatus.PENDING
    },
)

/** A claim interrupted by a crash is requeued without refunding its attempt, so a crashing recording still stops. */
fun Memory.requeuedForTranscription(): Memory = copy(transcriptionStatus = TranscriptionStatus.PENDING)

/**
 * True when an active memory has a recording but no transcript and was never claimed by the pipeline. Legacy
 * memories, which predate the status field, read as DONE with zero attempts, so this finds them.
 */
fun Memory.isMissingTranscription(): Boolean =
    !deleted &&
        recordingPath != null &&
        transcription.isBlank() &&
        transcriptionStatus == TranscriptionStatus.DONE &&
        transcriptionAttempts == 0

data class MemoryDraft(
    val title: String,
    val summary: String,
    val transcription: String,
    val recordingPath: String? = null,
) {
    init {
        recordingPath?.let(::requireRelativeRecordingPath)
    }
}

private fun requireRelativeRecordingPath(path: String) {
    require(path.isNotBlank()) { "A recording path cannot be blank." }
    require(!path.startsWith("/") && !path.startsWith("\\")) {
        "A recording path must be relative to the app recordings directory."
    }
    require(!path.contains("://")) { "A recording path cannot be a URI." }
    require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "A recording path cannot start with a drive letter." }
    require(path.split('/', '\\').none { it == ".." }) { "A recording path cannot contain parent segments." }
}
