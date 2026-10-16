package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft

interface MemoryService {
    /** The active (not deleted) memories, newest first. */
    fun getState(): StateFlow<List<Memory>>

    /** Returns the memory with [id], including a deleted one until it is purged. */
    suspend fun findById(id: String): Memory?

    /** Every stored memory, including deleted ones that have not been purged yet. */
    suspend fun findAll(): List<Memory>

    /** Saves a new memory. When [id] is given it is used as the memory id, so a recording file can be paired with its memory. */
    suspend fun create(draft: MemoryDraft, id: String? = null): Memory

    suspend fun update(id: String, draft: MemoryDraft): Memory?

    /** Marks the memory as deleted. Returns true only when it was active before this call. */
    suspend fun delete(id: String): Boolean

    /** Permanently removes a memory that was already marked deleted. Returns true if it was removed. */
    suspend fun purge(id: String): Boolean
}
