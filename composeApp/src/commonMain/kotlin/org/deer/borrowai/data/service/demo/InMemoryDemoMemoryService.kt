package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.service.MemoryService

class InMemoryDemoMemoryService(
    initialMemories: List<Memory> = DemoMemoryContent.memories,
    private val epochClock: EpochClock,
) : MemoryService {
    private val mutex = Mutex()
    private val records = initialMemories.associateByTo(mutableMapOf(), Memory::id)
    private var nextGeneratedId = 1L
    private val mutableMemories = MutableStateFlow(records.values.sortedByDescending(Memory::createdAtEpochMillis))

    override val memories = mutableMemories.asStateFlow()

    override suspend fun findById(id: String): Memory? = mutex.withLock {
        records[id]
    }

    override suspend fun create(draft: MemoryDraft): Memory = mutex.withLock {
        val memory = Memory(
            id = nextId(),
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
        val existing = records[id] ?: return@withLock null
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
        if (records.remove(id) == null) return@withLock false
        publishMemories()
        true
    }

    private fun nextId(): String {
        while (true) {
            val id = "demo-memory-${nextGeneratedId++}"
            if (id !in records) return id
        }
    }

    private fun publishMemories() {
        mutableMemories.value = records.values.sortedByDescending(Memory::createdAtEpochMillis)
    }

    private object DemoMemoryContent {
        val memories = listOf(
            Memory(
                id = "release-ideas",
                createdAtEpochMillis = 1_791_718_800_000L,
                title = "Ideas for the first release",
                summary = "Keep the initial experience focused on recording, a readable transcript, and a short useful summary.",
                transcription = "For the first release, keep the capture experience clear and direct. Show the transcript while speaking, " +
                    "then make the finished note easy to scan. We can add more organization after the basic flow feels good.",
            ),
            Memory(
                id = "weekend-plan",
                createdAtEpochMillis = 1_791_713_100_000L,
                title = "Weekend trip checklist",
                summary = "Pack the charger, check the train time, and remember to bring a light jacket for the evening.",
                transcription = "Check the train departure before leaving home. Pack the phone charger, headphones, and the small notebook. " +
                    "The weather may cool down later, so bring a light jacket too.",
            ),
            Memory(
                id = "reading-notes",
                createdAtEpochMillis = 1_791_656_700_000L,
                title = "Reading notes: small habits",
                summary = "Small routines are easier to maintain when they are attached to an existing daily habit.",
                transcription = "A useful routine should be small enough to repeat on a busy day. Attach a new action to something that " +
                    "already happens, then review it after a week instead of trying to change everything at once.",
            ),
            Memory(
                id = "team-review",
                createdAtEpochMillis = 1_791_555_000_000L,
                title = "Questions for the team review",
                summary = "Ask which part of the prototype needs clarification and what should be tested next.",
                transcription = "During the review, ask where the prototype feels unclear and which task should be tested next. " +
                    "Write down any assumptions that need confirmation before making the next design pass.",
            ),
        )
    }
}
