package org.deer.borrowai.data.service

import arrow.core.Either
import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
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

    /** Replaces the content of an active memory. Returns [MemoryError.NotFound] when it is missing or deleted. */
    suspend fun update(id: String, draft: MemoryDraft): Either<MemoryError, Unit>

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
    suspend fun recordFinishedAttempt(id: String, error: String?): Either<MemoryError, Unit>

    /** Counts one finished attempt that produced a blank summary. Retried until the limit is reached. */
    suspend fun recordEmpty(id: String): Either<MemoryError, Unit>

    /** Stores [summary] on an active memory and marks it as summarized. */
    suspend fun recordSummary(id: String, summary: String): Either<MemoryError, Unit>

    /** Stops retrying an active memory by setting its attempt count to the limit and its status to GAVE_UP. */
    suspend fun giveUpSummary(id: String): Either<MemoryError, Unit>
}

/**
 * Defaults for concrete services. Kotlin does not inherit default arguments into overrides, so calls on a concrete
 * type that omit these arguments resolve here.
 */
suspend fun MemoryService.create(
    draft: MemoryDraft,
    id: String? = null,
): Either<MemoryError, Memory> = create(draft, id, TranscriptionStatus.DONE)
