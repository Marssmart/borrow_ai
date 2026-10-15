package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft

interface MemoryService {
    val memories: MutableStateFlow<List<Memory>>

    suspend fun findById(id: String): Memory?

    suspend fun create(draft: MemoryDraft): Memory

    suspend fun update(id: String, draft: MemoryDraft): Memory?

    suspend fun delete(id: String): Boolean
}
