package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft

interface MemoryService {
    fun getState(): StateFlow<List<Memory>>

    suspend fun findById(id: String): Memory?

    /** Saves a new memory. When [id] is given it is used as the memory id, so a recording file can be paired with its memory. */
    suspend fun create(draft: MemoryDraft, id: String? = null): Memory

    suspend fun update(id: String, draft: MemoryDraft): Memory?

    suspend fun delete(id: String): Boolean
}
