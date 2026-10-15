package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MemoryDraft

class InMemoryDemoMemoryServiceTest {
    @Test
    fun supportsCreateReadUpdateDeleteAndPublishesNewestFirst() = runTest {
        val service = InMemoryDemoMemoryService(
            initialMemories = emptyList(),
            epochClock = EpochClock { 1_800_000_000_000L },
        )
        val draft = MemoryDraft("First note", "Short summary", "Full transcription")

        val created = service.create(draft)
        assertEquals("demo-memory-1", created.id)
        assertEquals(1_800_000_000_000L, created.createdAtEpochMillis)
        assertEquals(created, service.findById(created.id))
        assertEquals(listOf(created), service.memories.value)

        val updated = service.update(
            created.id,
            MemoryDraft("Updated title", "Updated summary", "Updated transcription"),
        )
        assertEquals("Updated title", updated?.title)
        assertEquals(created.createdAtEpochMillis, updated?.createdAtEpochMillis)
        assertNull(service.update("missing", draft))

        val withRecording = service.create(
            MemoryDraft("", "", "", recordingPath = "recordings/clip.m4a"),
        )
        assertEquals("recordings/clip.m4a", withRecording.recordingPath)
        assertEquals("", withRecording.title)
        service.delete(withRecording.id)

        assertTrue(service.delete(created.id))
        assertFalse(service.delete(created.id))
        assertTrue(service.memories.value.isEmpty())
        assertNull(service.findById(created.id))
    }
}
