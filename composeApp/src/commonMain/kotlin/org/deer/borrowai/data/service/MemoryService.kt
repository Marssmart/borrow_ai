package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft

interface MemoryService {
    fun getState(): StateFlow<List<Memory>>

    suspend fun findById(id: String): Memory?

    suspend fun create(draft: MemoryDraft): Memory

    suspend fun update(id: String, draft: MemoryDraft): Memory?

    suspend fun delete(id: String): Boolean
}
