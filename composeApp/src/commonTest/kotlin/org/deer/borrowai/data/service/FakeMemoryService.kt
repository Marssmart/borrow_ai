package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft

class FakeMemoryService(
    initialMemories: List<Memory> = emptyList(),
    private val epochClock: EpochClock,
) : MemoryService {
    private val mutex = Mutex()
    private val records = initialMemories.associateByTo(mutableMapOf(), Memory::id)
    private var nextGeneratedId = 1L
    private val memories = MutableStateFlow(
        records.values.filterNot(Memory::deleted).sortedByDescending(Memory::createdAtEpochMillis),
    )

    override fun getState(): StateFlow<List<Memory>> = memories

    override suspend fun findById(id: String): Memory? = mutex.withLock {
        records[id]
    }

    override suspend fun findAll(): List<Memory> = mutex.withLock {
        records.values.toList()
    }

    override suspend fun create(draft: MemoryDraft, id: String?): Memory = mutex.withLock {
        val memory = Memory(
            id = id ?: nextId(),
            createdAtEpochMillis = epochClock.nowEpochMillis(),
            title = draft.title,
            summary = draft.summary,
            transcription = draft.transcription,
            recordingPath = draft.recordingPath,
        )
        records[memory.id] = memory
        publishMemories()
        memory
    }

    override suspend fun update(id: String, draft: MemoryDraft): Memory? = mutex.withLock {
        val existing = records[id]?.takeIf { !it.deleted } ?: return@withLock null
        val updated = existing.copy(
            title = draft.title,
            summary = draft.summary,
            transcription = draft.transcription,
            recordingPath = draft.recordingPath,
        )
        records[id] = updated
        publishMemories()
        updated
    }

    override suspend fun delete(id: String): Boolean = mutex.withLock {
        val existing = records[id]?.takeIf { !it.deleted } ?: return@withLock false
        records[id] = existing.copy(deleted = true)
        publishMemories()
        true
    }

    override suspend fun purge(id: String): Boolean = mutex.withLock {
        if (records[id]?.deleted != true) return@withLock false
        records.remove(id)
        publishMemories()
        true
    }

    private fun nextId(): String {
        while (true) {
            val id = "memory-${nextGeneratedId++}"
            if (id !in records) return id
        }
    }

    private fun publishMemories() {
        memories.value = records.values
            .filterNot(Memory::deleted)
            .sortedByDescending(Memory::createdAtEpochMillis)
    }
}
