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
}
