package org.deer.borrowai.data.service

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.MemoryEdit
import org.deer.borrowai.data.domain.SummaryStatus
import org.deer.borrowai.data.domain.TranscriptionStatus

class MemoryEditServiceTest {
    private fun service() = FakeMemoryService(epochClock = EpochClock { 0L })

    private suspend fun FakeMemoryService.create(status: TranscriptionStatus): Memory =
        create(MemoryDraft("t", "", "original"), id = "a", transcriptionStatus = status).getOrNull()!!

    @Test
    fun changedTranscriptIsRefusedWhilePending() = runTest {
        val service = service()
        val created = service.create(TranscriptionStatus.PENDING)

        val result = service.edit(created.id, MemoryEdit("t", "", "typed", emptyList()))

        assertTrue(result.isLeft())
        assertEquals(created, service.findById(created.id).getOrNull())
    }

    @Test
    fun changedTranscriptIsRefusedWhileInProgress() = runTest {
        val service = service()
        val created = service.create(TranscriptionStatus.IN_PROGRESS)

        assertTrue(service.edit(created.id, MemoryEdit("t", "", "typed", emptyList())).isLeft())
        assertEquals(created, service.findById(created.id).getOrNull())
    }

    @Test
    fun unchangedTranscriptIsAcceptedWhilePending() = runTest {
        val service = service()
        val created = service.create(TranscriptionStatus.PENDING)

        assertTrue(service.edit(created.id, MemoryEdit("renamed", "", "original", emptyList())).isRight())
        assertEquals("renamed", service.findById(created.id).getOrNull()?.title)
    }

    @Test
    fun summaryWrittenForAnOlderTranscriptIsDropped() = runTest {
        val service = service()
        val created = service.create(TranscriptionStatus.DONE)
        assertTrue(service.edit(created.id, MemoryEdit("t", "", "changed", emptyList())).isRight())

        assertTrue(service.recordSummary(created.id, "stale", basedOnTranscription = "original").isRight())

        val stored = service.findById(created.id).getOrNull()!!
        assertEquals("", stored.summary)
        assertEquals(SummaryStatus.PENDING, stored.summaryStatus)
    }

    @Test
    fun summaryWrittenForTheCurrentTranscriptIsStored() = runTest {
        val service = service()
        val created = service.create(TranscriptionStatus.DONE)

        assertTrue(service.recordSummary(created.id, "fresh", basedOnTranscription = "original").isRight())

        assertEquals("fresh", service.findById(created.id).getOrNull()?.summary)
    }

    @Test
    fun userChosenCategoriesAreNotRefilledAfterClearing() = runTest {
        val service = service()
        val created = service.create(TranscriptionStatus.DONE)
        assertTrue(service.edit(created.id, MemoryEdit("t", "", "original", listOf(1))).isRight())
        assertTrue(service.edit(created.id, MemoryEdit("t", "", "original", emptyList())).isRight())

        assertTrue(service.recordCategorization(created.id, listOf(4), basedOnTranscription = "original").isRight())

        val stored = service.findById(created.id).getOrNull()!!
        assertEquals(emptyList(), stored.categories)
        assertTrue(stored.categoriesEditedByUser)
        assertFalse(stored.isCategorizationRetryable)
    }
}
