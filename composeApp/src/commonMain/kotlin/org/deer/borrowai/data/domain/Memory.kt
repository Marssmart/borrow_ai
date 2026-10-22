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
 *
 * [summaryStatus] is the single source of truth for summary state. A non-blank summary is stored as
 * [SummaryStatus.SUMMARIZED]. [summaryAttempts] counts finished summarization attempts. Once it
 * reaches [MAX_SUMMARY_ATTEMPTS] the memory is [SummaryStatus.GAVE_UP] and the recovery job stops.
 *
 * [transcriptionStatus] tracks the transcript pipeline the same way [summaryStatus] tracks summaries.
 * [transcriptionAttempts] counts finished transcription attempts. [lastTranscriptionError] holds
 * `"<exception class>: <message>"` from the most recent failure, and is cleared on success.
 */
data class Memory(
    val id: String,
    val createdAtEpochMillis: Long,
    val title: String,
    val summary: String,
    val transcription: String,
    val recordingPath: String? = null,
    val deleted: Boolean = false,
    val summaryStatus: SummaryStatus = SummaryStatus.PENDING,
    val summaryAttempts: Int = 0,
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.DONE,
    val transcriptionAttempts: Int = 0,
    val lastTranscriptionError: String? = null,
) {
    init {
        recordingPath?.let(::requireRelativeRecordingPath)
        require(summaryAttempts >= 0) { "Summary attempts cannot be negative." }
        require(transcriptionAttempts >= 0) { "Transcription attempts cannot be negative." }
    }

    /** True when a summary is stored. Derived from [summaryStatus]. */
    val summarized: Boolean
        get() = summaryStatus == SummaryStatus.SUMMARIZED

    /** True when the recovery job may still try to summarize this memory. */
    val isSummaryRetryable: Boolean
        get() = !deleted &&
            summaryAttempts < MAX_SUMMARY_ATTEMPTS &&
            (summaryStatus == SummaryStatus.PENDING || summaryStatus == SummaryStatus.EMPTY)
}

enum class SummaryStatus {
    /** Not summarized yet and still eligible. */
    PENDING,

    /** A summary is stored. */
    SUMMARIZED,

    /** The last finished attempt produced a blank summary. Still retried until the attempt limit. */
    EMPTY,

    /** Attempts are exhausted, or there is no transcript to summarize from. Never retried. */
    GAVE_UP,
}

/** How many finished summarization attempts a memory gets before the recovery job gives up on it. */
const val MAX_SUMMARY_ATTEMPTS = 10

fun Memory.withTranscription(transcription: String): Memory = copy(
    transcription = transcription,
    transcriptionStatus = TranscriptionStatus.DONE,
    lastTranscriptionError = null,
)

fun Memory.withSummary(summary: String): Memory = copy(
    summary = summary,
    summaryStatus = SummaryStatus.SUMMARIZED,
)

/** The summary status for a memory created with [summary]. A non-blank summary is already summarized. */
fun summaryStatusFor(summary: String): SummaryStatus =
    if (summary.isNotBlank()) SummaryStatus.SUMMARIZED else SummaryStatus.PENDING

/**
 * The memory with its summary text replaced by an edit. A blank edit to a summarized memory makes it pending
 * again with fresh attempts, so the recovery job generates a new summary.
 */
fun Memory.withEditedSummary(summary: String): Memory = when {
    summary.isNotBlank() -> copy(summary = summary, summaryStatus = SummaryStatus.SUMMARIZED)
    summaryStatus == SummaryStatus.SUMMARIZED -> copy(
        summary = summary,
        summaryStatus = SummaryStatus.PENDING,
        summaryAttempts = 0,
    )
    else -> copy(summary = summary)
}

fun Memory.withGaveUp(): Memory = copy(
    summaryAttempts = MAX_SUMMARY_ATTEMPTS,
    summaryStatus = SummaryStatus.GAVE_UP,
)

/**
 * The memory after one more finished attempt. [empty] marks a blank summary. [error] is stored as the last
 * error, or the previous one is kept when it is null. Reaching the limit gives up.
 */
fun Memory.afterFinishedAttempt(error: String?, empty: Boolean): Memory {
    val attempts = summaryAttempts + 1
    val status = when {
        attempts >= MAX_SUMMARY_ATTEMPTS -> SummaryStatus.GAVE_UP
        empty -> SummaryStatus.EMPTY
        else -> SummaryStatus.PENDING
    }
    return copy(
        summaryAttempts = attempts,
        summaryStatus = status,
        lastTranscriptionError = error ?: lastTranscriptionError,
    )
}

/** Summary state derived for a memory saved before summary tracking existed. */
data class SummaryState(
    val summaryStatus: SummaryStatus,
    val summaryAttempts: Int,
)

/**
 * Maps a memory stored before summary tracking onto the new fields. A non-blank summary is summarized.
 * A memory with a transcript waits for summarization. A blank transcript with a recording stays retryable.
 * A blank memory with no recording can never change and gives up. Existing attempts are kept.
 */
fun migrateLegacySummaryState(
    summary: String,
    transcription: String,
    hasRecording: Boolean,
    legacyAttempts: Int,
): SummaryState = when {
    summary.isNotBlank() -> SummaryState(SummaryStatus.SUMMARIZED, legacyAttempts)
    transcription.isNotBlank() -> pending(legacyAttempts)
    hasRecording -> pending(legacyAttempts)
    else -> SummaryState(SummaryStatus.GAVE_UP, MAX_SUMMARY_ATTEMPTS)
}

private fun pending(attempts: Int): SummaryState =
    if (attempts >= MAX_SUMMARY_ATTEMPTS) {
        SummaryState(SummaryStatus.GAVE_UP, MAX_SUMMARY_ATTEMPTS)
    } else {
        SummaryState(SummaryStatus.PENDING, attempts)
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

fun Memory.withFinishedTranscription(text: String): Memory = withTranscription(text)

fun Memory.withFailedTranscription(error: String?): Memory = copy(
    transcriptionStatus = if (transcriptionAttempts >= MAX_TRANSCRIPTION_ATTEMPTS) {
        TranscriptionStatus.FAILED
    } else {
        TranscriptionStatus.PENDING
    },
    lastTranscriptionError = error ?: lastTranscriptionError,
)

/**
 * A claim interrupted by a crash is requeued without refunding its attempt, so a crashing recording still stops.
 * If the interrupted claim used the last attempt, the memory is FAILED instead of being claimed again.
 */
fun Memory.requeuedForTranscription(): Memory = copy(
    transcriptionStatus = if (transcriptionAttempts >= MAX_TRANSCRIPTION_ATTEMPTS) {
        TranscriptionStatus.FAILED
    } else {
        TranscriptionStatus.PENDING
    },
)

/**
 * True when an active memory has a recording but no transcript and is waiting to be transcribed. Legacy memories
 * whose batch transcription finished (even with a blank result) decode as DONE, so they are not picked up here.
 */
fun Memory.isMissingTranscription(): Boolean =
    !deleted &&
        recordingPath != null &&
        transcription.isBlank() &&
        transcriptionStatus == TranscriptionStatus.PENDING

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
