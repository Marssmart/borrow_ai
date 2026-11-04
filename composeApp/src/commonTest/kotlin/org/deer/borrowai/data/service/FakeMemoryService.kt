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
import org.deer.borrowai.data.domain.categorizationStatusFor
import org.deer.borrowai.data.domain.categoryIdsViolation
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.domain.afterFinishedAttempt
import org.deer.borrowai.data.domain.afterFinishedCategorizationAttempt
import org.deer.borrowai.data.domain.claimedForTranscription
import org.deer.borrowai.data.domain.isMissingTranscription
import org.deer.borrowai.data.domain.requeuedForTranscription
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.domain.claimedForSpeakerAnalysis
import org.deer.borrowai.data.domain.requeuedForSpeakerAnalysis
import org.deer.borrowai.data.domain.speakerStatusFor
import org.deer.borrowai.data.domain.withFailedSpeakerAnalysis
import org.deer.borrowai.data.domain.withFinishedSpeakerAnalysis
import org.deer.borrowai.data.domain.withSpeakerSkipped
import org.deer.borrowai.data.domain.summaryStatusFor
import org.deer.borrowai.data.domain.withCategories
import org.deer.borrowai.data.domain.withCategorizationGaveUp
import org.deer.borrowai.data.domain.MemoryEdit
import org.deer.borrowai.data.domain.isTranscriptEditable
import org.deer.borrowai.data.domain.memoryEditViolation
import org.deer.borrowai.data.domain.unlessCategoriesEdited
import org.deer.borrowai.data.domain.unlessSummaryEdited
import org.deer.borrowai.data.domain.unlessTranscriptChanged
import org.deer.borrowai.data.domain.withCategorizationRequested
import org.deer.borrowai.data.domain.withEdit
import org.deer.borrowai.data.domain.withSummaryRequested
import org.deer.borrowai.data.domain.withFailedTranscription
import org.deer.borrowai.data.domain.withFinishedTranscription
import org.deer.borrowai.data.domain.withGaveUp
import org.deer.borrowai.data.domain.withSummary
import org.deer.borrowai.data.domain.withUserCategories

class FakeMemoryService(
    initialMemories: List<Memory> = emptyList(),
    private val epochClock: EpochClock,
) : MemoryService {
    private val mutex = Mutex()
    private val records = initialMemories.associateByTo(mutableMapOf(), Memory::id)
    private val categories = mutableMapOf<Int, Category>()
    /** Ids passed to [delete], in call order. */
    val deleteCalls = mutableListOf<String>()
    /** When set, [delete] returns this error without changing the memory. */
    var deleteFailure: MemoryError? = null
    /** When set, [requestSummary] returns this error without changing the memory. */
    var requestFailure: MemoryError? = null
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
            durationMillis = draft.durationMillis,
            transcriptionStatus = transcriptionStatus,
            categories = draft.categories,
            categorizationStatus = categorizationStatusFor(draft.categories),
            speakerStatus = speakerStatusFor(draft.recordingPath),
        )
        records[memory.id] = memory
        publishMemories()
        memory.right()
    }

    override suspend fun updateCategories(id: String, categories: List<Int>): Either<MemoryError, Unit> {
        categoryIdsViolation(categories)?.let { return MemoryError.InvalidInput(it).left() }
        return updateActive(id) { it.withUserCategories(categories) }
    }

    override suspend fun edit(id: String, edit: MemoryEdit): Either<MemoryError, Unit> = mutex.withLock {
        val existing = records[id]?.takeIf { !it.deleted } ?: return@withLock MemoryError.NotFound(id).left()
        memoryEditViolation(edit, existing.transcriptionStatus)?.let {
            return@withLock MemoryError.InvalidInput(it).left()
        }
        if (!existing.transcriptionStatus.isTranscriptEditable() && edit.transcription != existing.transcription) {
            return@withLock MemoryError.InvalidInput("The transcript is still being created.").left()
        }
        records[id] = existing.withEdit(edit)
        publishMemories()
        Unit.right()
    }

    override suspend fun requestSummary(id: String): Either<MemoryError, Unit> {
        requestFailure?.let { return it.left() }
        return updateActive(id) { it.withSummaryRequested() }
    }

    override suspend fun requestCategorization(id: String): Either<MemoryError, Unit> =
        updateActive(id) { it.withCategorizationRequested() }

    override suspend fun findCategories(): Either<MemoryError, List<Category>> = mutex.withLock {
        categories.values.sortedBy(Category::id).right()
    }

    override suspend fun recordDuration(id: String, durationMillis: Long): Either<MemoryError, Unit> = mutex.withLock {
        val memory = records[id]?.takeIf { !it.deleted } ?: return@withLock MemoryError.NotFound(id).left()
        if (memory.durationMillis == null) records[id] = memory.copy(durationMillis = durationMillis)
        publishMemories()
        Unit.right()
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
        deleteCalls += id
        deleteFailure?.let { return@withLock it.left() }
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

    override suspend fun recordFinishedAttempt(
        id: String,
        error: String?,
        basedOnTranscription: String?,
    ): Either<MemoryError, Unit> = updateActive(id) {
        it.unlessSummaryEdited { m ->
            m.unlessTranscriptChanged(basedOnTranscription) { x -> x.afterFinishedAttempt(error = error, empty = false) }
        }
    }

    override suspend fun recordEmpty(id: String, basedOnTranscription: String?): Either<MemoryError, Unit> =
        updateActive(id) {
            it.unlessSummaryEdited { m ->
                m.unlessTranscriptChanged(basedOnTranscription) { x -> x.afterFinishedAttempt(error = null, empty = true) }
            }
        }

    override suspend fun recordSummary(
        id: String,
        summary: String,
        basedOnTranscription: String?,
    ): Either<MemoryError, Unit> = updateActive(id) {
        it.unlessSummaryEdited { m -> m.unlessTranscriptChanged(basedOnTranscription) { x -> x.withSummary(summary) } }
    }

    override suspend fun giveUpSummary(id: String): Either<MemoryError, Unit> =
        updateActive(id) { it.unlessSummaryEdited { m -> m.withGaveUp() } }

    override suspend fun recordCategorization(
        id: String,
        categories: List<Int>,
        basedOnTranscription: String?,
    ): Either<MemoryError, Unit> {
        categoryIdsViolation(categories)?.let { return MemoryError.InvalidInput(it).left() }
        return updateActive(id) {
            it.unlessCategoriesEdited { m ->
                m.unlessTranscriptChanged(basedOnTranscription) { x -> x.withCategories(categories) }
            }
        }
    }

    override suspend fun recordCategorizationAttempt(
        id: String,
        empty: Boolean,
        basedOnTranscription: String?,
    ): Either<MemoryError, Unit> = updateActive(id) {
        it.unlessCategoriesEdited { m ->
            m.unlessTranscriptChanged(basedOnTranscription) { x -> x.afterFinishedCategorizationAttempt(empty = empty) }
        }
    }

    override suspend fun giveUpCategorization(id: String): Either<MemoryError, Unit> =
        updateActive(id) { it.unlessCategoriesEdited { m -> m.withCategorizationGaveUp() } }

    override suspend fun claimNextPendingSpeakerAnalysis(): Either<MemoryError, Memory?> = mutex.withLock {
        val next = records.values
            .filter { !it.deleted && it.speakerStatus == SpeakerStatus.PENDING }
            .maxByOrNull(Memory::createdAtEpochMillis)
            ?.claimedForSpeakerAnalysis()
        if (next != null) records[next.id] = next
        next.right()
    }

    override suspend fun completeSpeakerAnalysis(id: String, analysisVersion: Int): Either<MemoryError, Unit> =
        updateActive(id) { it.withFinishedSpeakerAnalysis(analysisVersion) }

    override suspend fun failSpeakerAnalysis(id: String, error: String?): Either<MemoryError, Unit> =
        updateActive(id) { it.withFailedSpeakerAnalysis(error) }

    override suspend fun skipSpeakerAnalysis(id: String): Either<MemoryError, Unit> =
        updateActive(id) { it.withSpeakerSkipped() }

    override suspend fun requeueUnfinishedSpeakerAnalyses(): Either<MemoryError, Int> = mutex.withLock {
        var requeued = 0
        records.replaceAll { _, memory ->
            if (!memory.deleted && memory.speakerStatus == SpeakerStatus.ANALYZING) {
                requeued++
                memory.requeuedForSpeakerAnalysis()
            } else {
                memory
            }
        }
        publishMemories()
        requeued.right()
    }

    override suspend fun skipPendingSpeakerAnalyses(): Either<MemoryError, Int> = mutex.withLock {
        var skipped = 0
        records.replaceAll { _, memory ->
            if (!memory.deleted && memory.speakerStatus == SpeakerStatus.PENDING) {
                skipped++
                memory.withSpeakerSkipped()
            } else {
                memory
            }
        }
        publishMemories()
        skipped.right()
    }

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
