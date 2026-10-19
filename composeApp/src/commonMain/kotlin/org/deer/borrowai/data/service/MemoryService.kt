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

    /** Records a failed attempt: back to PENDING while attempts remain, otherwise FAILED. */
    suspend fun failTranscription(id: String): Either<MemoryError, Unit>

    /**
     * Sweeps transcription work left unfinished: IN_PROGRESS claims go back to PENDING, and legacy memories that have a
     * recording but no transcript and no recorded attempts are queued. Returns how many memories were queued.
     */
    suspend fun requeueUnfinishedTranscriptions(): Either<MemoryError, Int>
}
