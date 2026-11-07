package org.deer.borrowai.data.service

import arrow.core.Either
import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.domain.Category
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.MemoryEdit
import org.deer.borrowai.data.domain.SpeakerSkipReason
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.domain.TranscriptionStatus

interface MemoryService {
    /** The active (not deleted) memories, newest first. */
    fun getState(): StateFlow<List<Memory>>

    /** Returns the memory with [id], including a deleted one until it is purged. */
    suspend fun findById(id: String): Either<MemoryError, Memory>

    /** Every stored memory, including deleted ones that have not been purged yet. */
    suspend fun findAll(): Either<MemoryError, List<Memory>>

    /**
     * Saves a new memory. When [id] is given it is used as the memory id, so a recording file can be paired with its memory.
     * [transcriptionStatus] is [TranscriptionStatus.PENDING] when the transcript still has to be generated.
     */
    suspend fun create(
        draft: MemoryDraft,
        id: String? = null,
        transcriptionStatus: TranscriptionStatus = TranscriptionStatus.DONE,
    ): Either<MemoryError, Memory>

    /**
     * Saves the edited fields of an active memory in one write. A summary or category list that changed is marked as
     * the user's, so the recovery jobs leave it alone. A transcript change is refused while it is still being created.
     * Returns [MemoryError.NotFound] when the memory is missing or deleted, and [MemoryError.InvalidInput] when a rule
     * fails.
     */
    suspend fun edit(id: String, edit: MemoryEdit): Either<MemoryError, Unit>

    /** Clears the user's summary marker and sets the summary back to pending, so a new one is written. */
    suspend fun requestSummary(id: String): Either<MemoryError, Unit>

    /** Clears the user's category marker and sets the categories back to pending, so new ones are chosen. */
    suspend fun requestCategorization(id: String): Either<MemoryError, Unit>

    /**
     * Replaces only the category ids of an active memory. Every other field, including the summary status, is kept.
     * Returns [MemoryError.NotFound] when it is missing or deleted.
     */
    suspend fun updateCategories(id: String, categories: List<Int>): Either<MemoryError, Unit>

    /** Stores [durationMillis] for an active memory that has none. A stored duration is never overwritten. */
    suspend fun recordDuration(id: String, durationMillis: Long): Either<MemoryError, Unit>

    /** Marks the memory as deleted. Returns [MemoryError.NotFound] when it is missing or already deleted. */
    suspend fun delete(id: String): Either<MemoryError, Unit>

    /** Permanently removes a memory that was already marked deleted. Returns true if it was removed. */
    suspend fun purge(id: String): Boolean

    /**
     * Atomically claims the oldest [TranscriptionStatus.PENDING] memory: sets it to IN_PROGRESS and counts the attempt.
     * Returns null when nothing is pending.
     */
    suspend fun claimNextPendingTranscription(): Either<MemoryError, Memory?>

    /** Stores [transcription] and marks the memory DONE. Returns [MemoryError.NotFound] when it is missing or deleted. */
    suspend fun completeTranscription(id: String, transcription: String): Either<MemoryError, Unit>

    /** Records a failed attempt with [error] as the reason: back to PENDING while attempts remain, otherwise FAILED. */
    suspend fun failTranscription(id: String, error: String?): Either<MemoryError, Unit>

    /**
     * Sweeps transcription work left unfinished: IN_PROGRESS claims go back to PENDING, and legacy memories that have a
     * recording but no transcript and no recorded attempts are queued. Returns how many memories were queued.
     */
    suspend fun requeueUnfinishedTranscriptions(): Either<MemoryError, Int>

    /**
     * Counts one finished summarization attempt that did not produce a summary. [error] is stored as the
     * last error when it is not null. The memory gives up once the limit is reached.
     */
    suspend fun recordFinishedAttempt(
        id: String,
        error: String?,
        basedOnTranscription: String? = null,
    ): Either<MemoryError, Unit>

    /** Counts one finished attempt that produced a blank summary. Retried until the limit is reached. */
    suspend fun recordEmpty(id: String, basedOnTranscription: String? = null): Either<MemoryError, Unit>

    /** Stores [summary] on an active memory and marks it as summarized. */
    suspend fun recordSummary(
        id: String,
        summary: String,
        basedOnTranscription: String? = null,
    ): Either<MemoryError, Unit>

    /** Stops retrying an active memory by setting its attempt count to the limit and its status to GAVE_UP. */
    suspend fun giveUpSummary(id: String): Either<MemoryError, Unit>

    /** Stores [categories] on an active memory and marks its categorization as done. */
    suspend fun recordCategorization(
        id: String,
        categories: List<Int>,
        basedOnTranscription: String? = null,
    ): Either<MemoryError, Unit>

    /**
     * Counts one finished categorization attempt that produced no categories. [empty] marks a blank transcription.
     * The memory gives up once the limit is reached.
     */
    suspend fun recordCategorizationAttempt(
        id: String,
        empty: Boolean,
        basedOnTranscription: String? = null,
    ): Either<MemoryError, Unit>

    /** Stops retrying an active memory's categorization by setting its status to GAVE_UP. */
    suspend fun giveUpCategorization(id: String): Either<MemoryError, Unit>

    /**
     * Atomically claims the newest memory whose speaker analysis is [SpeakerStatus.PENDING]: sets it to ANALYZING and
     * counts the attempt. Returns null when nothing is pending.
     */
    suspend fun claimNextPendingSpeakerAnalysis(): Either<MemoryError, Memory?>

    /** Marks the speaker analysis of an active memory DONE and stores the [analysisVersion] it was written with. */
    suspend fun completeSpeakerAnalysis(id: String, analysisVersion: Int): Either<MemoryError, Unit>

    /** Records a failed attempt with [error] as the reason: back to PENDING while attempts remain, otherwise FAILED. */
    suspend fun failSpeakerAnalysis(id: String, error: String?): Either<MemoryError, Unit>

    /** Returns an active analysis claim to PENDING without charging an attempt, for example when recording starts. */
    suspend fun yieldSpeakerAnalysis(id: String): Either<MemoryError, Unit>

    /** Marks an active memory SKIPPED. A finished analysis is kept, so a later change of setting cannot hide it. */
    suspend fun skipSpeakerAnalysis(
        id: String,
        reason: SpeakerSkipReason = SpeakerSkipReason.NOT_ANALYZED,
    ): Either<MemoryError, Unit>

    /**
     * Returns analyses left ANALYZING by an interrupted run to PENDING, or to FAILED when their attempts are used up.
     * Returns how many were requeued.
     */
    suspend fun requeueUnfinishedSpeakerAnalyses(): Either<MemoryError, Int>

    /**
     * Requeues completed analyses written by an older [analysisVersion] when their recording is still available.
     * Existing occurrences remain in place until the new analysis replaces them.
     */
    suspend fun requeueOutdatedSpeakerAnalyses(analysisVersion: Int): Either<MemoryError, Int>

    /** Marks every PENDING speaker analysis SKIPPED. Used while speaker analysis cannot run. Returns how many changed. */
    suspend fun skipPendingSpeakerAnalyses(): Either<MemoryError, Int>

    /** Requeues skipped memories with recordings after consent or model availability changes. */
    suspend fun requeueSkippedSpeakerAnalyses(): Either<MemoryError, Int>

    /** Clears analysis status and retries after speaker data is withdrawn. */
    suspend fun resetSpeakerAnalyses(): Either<MemoryError, Int>


    /** Every row of the category master table, ordered by id. */
    suspend fun findCategories(): Either<MemoryError, List<Category>>

    /**
     * Adds a row to the category master table with the next id (the highest existing id plus one, starting at 1).
     * Returns [MemoryError.InvalidInput] when [name] or [description] break the [Category] rules.
     */
    suspend fun createCategory(name: String, description: String): Either<MemoryError, Category>
}

/**
 * Defaults for concrete services. Kotlin does not inherit default arguments into overrides, so calls on a concrete
 * type that omit these arguments resolve here.
 */
suspend fun MemoryService.create(
    draft: MemoryDraft,
    id: String? = null,
): Either<MemoryError, Memory> = create(draft, id, TranscriptionStatus.DONE)
