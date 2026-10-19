package org.deer.borrowai.data.service

import arrow.core.Either
import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft

interface MemoryService {
    /** The active (not deleted) memories, newest first. */
    fun getState(): StateFlow<List<Memory>>

    /** Returns the memory with [id], including a deleted one until it is purged. */
    suspend fun findById(id: String): Either<MemoryError, Memory>

    /** Every stored memory, including deleted ones that have not been purged yet. */
    suspend fun findAll(): Either<MemoryError, List<Memory>>

    /** Saves a new memory. When [id] is given it is used as the memory id, so a recording file can be paired with its memory. */
    suspend fun create(draft: MemoryDraft, id: String? = null): Either<MemoryError, Memory>

    /** Replaces the content of an active memory. Returns [MemoryError.NotFound] when it is missing or deleted. */
    suspend fun update(id: String, draft: MemoryDraft): Either<MemoryError, Unit>

    /** Marks the memory as deleted. Returns [MemoryError.NotFound] when it is missing or already deleted. */
    suspend fun delete(id: String): Either<MemoryError, Unit>

    /** Permanently removes a memory that was already marked deleted. Returns true if it was removed. */
    suspend fun purge(id: String): Boolean

    /** Stores the finished transcript of an active memory, even when [text] is blank. Clears the last transcription error. */
    suspend fun recordTranscription(id: String, text: String): Either<MemoryError, Unit>

    /** Stores [error] as the last transcription error of an active memory. Not an attempt on its own. */
    suspend fun recordTranscriptionFailure(id: String, error: String): Either<MemoryError, Unit>

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
