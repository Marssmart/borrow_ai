package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.deer.borrowai.data.domain.Category
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.domain.afterFinishedAttempt
import org.deer.borrowai.data.domain.claimedForTranscription
import org.deer.borrowai.data.domain.isMissingTranscription
import org.deer.borrowai.data.domain.requeuedForTranscription
import org.deer.borrowai.data.domain.summaryStatusFor
import org.deer.borrowai.data.domain.withEditedSummary
import org.deer.borrowai.data.domain.withFailedTranscription
import org.deer.borrowai.data.domain.withFinishedTranscription
import org.deer.borrowai.data.domain.withGaveUp
import org.deer.borrowai.data.domain.withSummary

class FakeMemoryService(
    initialMemories: List<Memory> = emptyList(),
    private val epochClock: EpochClock,
) : MemoryService {
    private val mutex = Mutex()
    private val records = initialMemories.associateByTo(mutableMapOf(), Memory::id)
    private val categories = mutableMapOf<Int, Category>()
    private var nextGeneratedId = 1L
    private val memories = MutableStateFlow(
        records.values.filterNot(Memory::deleted).sortedByDescending(Memory::createdAtEpochMillis),
    )

    override fun getState(): StateFlow<List<Memory>> = memories

    override suspend fun findById(id: String): Either<MemoryError, Memory> = mutex.withLock {
        records[id]?.right() ?: MemoryError.NotFound(id).left()
    }

    override suspend fun findAll(): Either<MemoryError, List<Memory>> = mutex.withLock {
        records.values.toList().right()
    }

    override suspend fun create(
        draft: MemoryDraft,
        id: String?,
        transcriptionStatus: TranscriptionStatus,
    ): Either<MemoryError, Memory> = mutex.withLock {
        val memory = Memory(
            id = id ?: nextId(),
            createdAtEpochMillis = epochClock.nowEpochMillis(),
            title = draft.title,
            summary = draft.summary,
            summaryStatus = summaryStatusFor(draft.summary),
            transcription = draft.transcription,
            recordingPath = draft.recordingPath,
            transcriptionStatus = transcriptionStatus,
            categories = draft.categories,
        )
        records[memory.id] = memory
        publishMemories()
        memory.right()
    }

    override suspend fun update(id: String, draft: MemoryDraft): Either<MemoryError, Unit> = mutex.withLock {
        val existing = records[id]?.takeIf { !it.deleted } ?: return@withLock MemoryError.NotFound(id).left()
        val updated = existing.withEditedSummary(draft.summary).copy(
            title = draft.title,
            transcription = draft.transcription,
            recordingPath = draft.recordingPath,
            categories = draft.categories,
        )
        records[id] = updated
        publishMemories()
        Unit.right()
    }

    override suspend fun findCategories(): Either<MemoryError, List<Category>> = mutex.withLock {
        categories.values.sortedBy(Category::id).right()
    }

    override suspend fun createCategory(name: String, description: String): Either<MemoryError, Category> =
        mutex.withLock {
            try {
                val category = Category(
                    id = (categories.keys.maxOrNull() ?: 0) + 1,
                    name = name,
                    description = description,
                )
                categories[category.id] = category
                category.right()
            } catch (e: IllegalArgumentException) {
                MemoryError.InvalidInput(e.message.orEmpty()).left()
            }
        }

    override suspend fun delete(id: String): Either<MemoryError, Unit> = mutex.withLock {
        val existing = records[id]?.takeIf { !it.deleted } ?: return@withLock MemoryError.NotFound(id).left()
        records[id] = existing.copy(deleted = true)
        publishMemories()
        Unit.right()
    }

    override suspend fun purge(id: String): Boolean = mutex.withLock {
        if (records[id]?.deleted != true) return@withLock false
        records.remove(id)
        publishMemories()
        true
    }

    override suspend fun claimNextPendingTranscription(): Either<MemoryError, Memory?> = mutex.withLock {
        val next = records.values
            .filter { !it.deleted && it.transcriptionStatus == TranscriptionStatus.PENDING }
            .minByOrNull(Memory::createdAtEpochMillis)
            ?.claimedForTranscription()
        if (next != null) records[next.id] = next
        next.right()
    }

    override suspend fun completeTranscription(id: String, transcription: String): Either<MemoryError, Unit> =
        updateActive(id) { it.withFinishedTranscription(transcription) }

    override suspend fun failTranscription(id: String, error: String?): Either<MemoryError, Unit> =
        updateActive(id) { it.withFailedTranscription(error) }

    override suspend fun requeueUnfinishedTranscriptions(): Either<MemoryError, Int> = mutex.withLock {
        var requeued = 0
        records.replaceAll { _, memory ->
            when {
                memory.deleted -> memory
                memory.transcriptionStatus == TranscriptionStatus.IN_PROGRESS -> {
                    requeued++
                    memory.requeuedForTranscription()
                }
                memory.isMissingTranscription() -> {
                    requeued++
                    memory.copy(transcriptionStatus = TranscriptionStatus.PENDING)
                }
                else -> memory
            }
        }
        publishMemories()
        requeued.right()
    }

    override suspend fun recordFinishedAttempt(id: String, error: String?): Either<MemoryError, Unit> = updateActive(id) {
        it.afterFinishedAttempt(error = error, empty = false)
    }

    override suspend fun recordEmpty(id: String): Either<MemoryError, Unit> = updateActive(id) {
        it.afterFinishedAttempt(error = null, empty = true)
    }

    override suspend fun recordSummary(id: String, summary: String): Either<MemoryError, Unit> = updateActive(id) {
        it.withSummary(summary)
    }

    override suspend fun giveUpSummary(id: String): Either<MemoryError, Unit> = updateActive(id) { it.withGaveUp() }

    private suspend fun updateActive(id: String, change: (Memory) -> Memory): Either<MemoryError, Unit> =
        mutex.withLock {
            val existing = records[id]?.takeIf { !it.deleted } ?: return@withLock MemoryError.NotFound(id).left()
            records[id] = change(existing)
            publishMemories()
            Unit.right()
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
