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
 * [summarized] is true only when a non-blank summary is stored, and it always equals
 * `summaryStatus == SUMMARIZED`. [summaryAttempts] counts finished summarization attempts. Once it
 * reaches [MAX_SUMMARY_ATTEMPTS] the memory is [SummaryStatus.GAVE_UP] and the recovery job stops.
 *
 * [transcriptionDone] is true once the transcriber has finished, even when the text is blank. It tells
 * "not transcribed yet" apart from "transcribed and blank". [lastTranscriptionError] holds
 * `"<exception class>: <message>"` from the most recent failure. It is cleared on success.
 */
data class Memory(
    val id: String,
    val createdAtEpochMillis: Long,
    val title: String,
    val summary: String,
    val transcription: String,
    val recordingPath: String? = null,
    val deleted: Boolean = false,
    val summarized: Boolean = false,
    val summaryStatus: SummaryStatus = SummaryStatus.PENDING,
    val summaryAttempts: Int = 0,
    val transcriptionDone: Boolean = false,
    val lastTranscriptionError: String? = null,
) {
    init {
        recordingPath?.let(::requireRelativeRecordingPath)
        require(summaryAttempts >= 0) { "Summary attempts cannot be negative." }
        require(summarized == (summaryStatus == SummaryStatus.SUMMARIZED)) {
            "summarized must match summaryStatus SUMMARIZED."
        }
    }

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

/** Stored in [Memory.lastTranscriptionError] while a recording has not been transcribed yet. */
const val TRANSCRIPTION_PENDING_ERROR = "TranscriptionPending: not transcribed yet"

fun Memory.withTranscription(transcription: String): Memory = copy(
    transcription = transcription,
    transcriptionDone = true,
    lastTranscriptionError = null,
)

fun Memory.withTranscriptionFailure(error: String): Memory = copy(lastTranscriptionError = error)

fun Memory.withSummary(summary: String): Memory = copy(
    summary = summary,
    summarized = true,
    summaryStatus = SummaryStatus.SUMMARIZED,
)

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
    val summarized: Boolean,
    val summaryStatus: SummaryStatus,
    val summaryAttempts: Int,
    val transcriptionDone: Boolean,
)

/**
 * Maps a memory stored before summary tracking onto the new fields. A non-blank summary is summarized.
 * A non-blank transcript is done and waits for summarization. A blank transcript with a recording is not
 * transcribed yet, so it stays retryable. A blank memory with no recording can never change and gives up.
 * Existing attempts are kept.
 */
fun migrateLegacySummaryState(
    summary: String,
    transcription: String,
    hasRecording: Boolean,
    legacyAttempts: Int,
): SummaryState = when {
    summary.isNotBlank() -> SummaryState(true, SummaryStatus.SUMMARIZED, legacyAttempts, true)
    transcription.isNotBlank() -> pending(legacyAttempts, transcriptionDone = true)
    hasRecording -> pending(legacyAttempts, transcriptionDone = false)
    else -> SummaryState(false, SummaryStatus.GAVE_UP, MAX_SUMMARY_ATTEMPTS, true)
}

private fun pending(attempts: Int, transcriptionDone: Boolean): SummaryState =
    if (attempts >= MAX_SUMMARY_ATTEMPTS) {
        SummaryState(false, SummaryStatus.GAVE_UP, MAX_SUMMARY_ATTEMPTS, transcriptionDone)
    } else {
        SummaryState(false, SummaryStatus.PENDING, attempts, transcriptionDone)
    }

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
