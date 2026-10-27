package org.deer.borrowai.data.service.category

import arrow.core.Either
import arrow.core.left
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.CategorizationStatus
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MAX_CATEGORIZATION_ATTEMPTS
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.MemoryError

class MemoryCategorizationRecoveryJobTest {
    private class ScriptedCategorizer(
        private val result: suspend (String) -> CategorizationResult,
    ) : MemoryCategorizer {
        var calls = 0

        override suspend fun categorize(transcription: String): CategorizationResult {
            calls++
            return result(transcription)
        }
    }

    private fun memory(
        id: String,
        transcription: String = "hello there",
        categories: List<Int> = emptyList(),
        categorizationStatus: CategorizationStatus = if (categories.isNotEmpty()) {
            CategorizationStatus.CATEGORIZED
        } else {
            CategorizationStatus.PENDING
        },
        categorizationAttempts: Int = 0,
        deleted: Boolean = false,
    ) = Memory(
        id = id,
        createdAtEpochMillis = 1_800_000_000_000L,
        title = "Title $id",
        summary = "",
        transcription = transcription,
        recordingPath = "$id.m4a",
        deleted = deleted,
        categories = categories,
        categorizationStatus = categorizationStatus,
        categorizationAttempts = categorizationAttempts,
    )

    private fun service(vararg memories: Memory) = FakeMemoryService(
        initialMemories = memories.toList(),
        epochClock = EpochClock { 1_800_000_000_000L },
    )

    private fun job(
        service: MemoryService,
        categorizer: MemoryCategorizer,
        isRecording: () -> Boolean = { false },
        timeoutMillis: Long = 60_000L,
        batchSize: Int = 5,
    ) = MemoryCategorizationRecoveryJob(
        memoryService = service,
        categorizer = categorizer,
        isRecording = isRecording,
        timeoutMillis = timeoutMillis,
        batchSize = batchSize,
    )

    private suspend fun FakeMemoryService.get(id: String): Memory = findById(id).getOrNull()!!

    @Test
    fun storesCategoriesAndMarksMemoryCategorized() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer { CategorizationResult.Categories(listOf(1, 2)) }

        val stored = job(service, categorizer).recover()

        assertEquals(1, stored)
        val updated = service.get("a")
        assertEquals(listOf(1, 2), updated.categories)
        assertEquals(CategorizationStatus.CATEGORIZED, updated.categorizationStatus)
        assertEquals(0, updated.categorizationAttempts)
    }

    @Test
    fun overlappingRunsDoNotCategorizeTheSameMemoryTwice() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer {
            delay(50)
            CategorizationResult.Categories(listOf(1))
        }
        val job = job(service, categorizer)

        val first = async { job.recover() }
        val second = async { job.recover() }

        assertEquals(1, first.await() + second.await())
        assertEquals(1, categorizer.calls)
    }

    @Test
    fun categoriesWithBackfillFailureAreStored() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer {
            CategorizationResult.Categories(listOf(4), backfillFailure = IllegalStateException("backfill"))
        }

        assertEquals(1, job(service, categorizer).recover())

        assertEquals(listOf(4), service.get("a").categories)
    }

    @Test
    fun emptyResultIsCountedAndRetriedUntilLimit() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer { CategorizationResult.Empty }
        val recovery = job(service, categorizer)

        assertEquals(0, recovery.recover())
        val afterFirst = service.get("a")
        assertEquals(1, afterFirst.categorizationAttempts)
        assertEquals(CategorizationStatus.EMPTY, afterFirst.categorizationStatus)
        assertTrue(afterFirst.categories.isEmpty())

        assertEquals(0, recovery.recover())
        assertEquals(2, service.get("a").categorizationAttempts)
    }

    @Test
    fun failedAttemptIsCountedAndMemoryStaysRetryable() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer { CategorizationResult.Failed(IllegalStateException("boom")) }

        assertEquals(0, job(service, categorizer).recover())

        val updated = service.get("a")
        assertEquals(1, updated.categorizationAttempts)
        assertEquals(CategorizationStatus.PENDING, updated.categorizationStatus)
        assertTrue(updated.isCategorizationRetryable)
    }

    @Test
    fun unavailableLeavesMemoryUntouched() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer { CategorizationResult.Unavailable }

        assertEquals(0, job(service, categorizer).recover())

        val updated = service.get("a")
        assertEquals(0, updated.categorizationAttempts)
        assertEquals(CategorizationStatus.PENDING, updated.categorizationStatus)
    }

    @Test
    fun blankTranscriptionIsSkippedWithoutCallingCategorizer() = runTest {
        val service = service(memory("a", transcription = "   "))
        val categorizer = ScriptedCategorizer { CategorizationResult.Categories(listOf(1)) }

        assertEquals(0, job(service, categorizer).recover())

        assertEquals(0, categorizer.calls)
        assertEquals(0, service.get("a").categorizationAttempts)
    }

    @Test
    fun deletedAndAlreadyCategorizedMemoriesAreSkipped() = runTest {
        val service = service(
            memory("deleted", deleted = true),
            memory("done", categories = listOf(2)),
        )
        val categorizer = ScriptedCategorizer { CategorizationResult.Categories(listOf(1)) }

        assertEquals(0, job(service, categorizer).recover())

        assertEquals(0, categorizer.calls)
        assertEquals(listOf(2), service.get("done").categories)
    }

    @Test
    fun processesAtMostOneBatchPerRun() = runTest {
        val service = service(memory("a"), memory("b"), memory("c"))
        val categorizer = ScriptedCategorizer { CategorizationResult.Categories(listOf(1)) }
        val recovery = job(service, categorizer, batchSize = 2)

        assertEquals(2, recovery.recover())
        assertEquals(2, categorizer.calls)

        assertEquals(1, recovery.recover())
        assertEquals(3, categorizer.calls)
    }

    @Test
    fun modelMissingStopsBatchWithoutCountingAttempt() = runTest {
        val service = service(memory("a"), memory("b"))
        val categorizer = ScriptedCategorizer { CategorizationResult.ModelMissing }

        assertEquals(0, job(service, categorizer).recover())

        assertEquals(1, categorizer.calls)
        assertEquals(0, service.get("a").categorizationAttempts)
        assertEquals(0, service.get("b").categorizationAttempts)
    }

    @Test
    fun attemptsStopAtLimitAcrossRuns() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer { CategorizationResult.Failed(IllegalStateException("boom")) }
        val recovery = job(service, categorizer)

        repeat(MAX_CATEGORIZATION_ATTEMPTS + 2) { recovery.recover() }

        assertEquals(MAX_CATEGORIZATION_ATTEMPTS, categorizer.calls)
        assertEquals(MAX_CATEGORIZATION_ATTEMPTS, service.get("a").categorizationAttempts)
    }

    @Test
    fun runsNothingWhileRecording() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer { CategorizationResult.Categories(listOf(1)) }

        assertEquals(0, job(service, categorizer, isRecording = { true }).recover())

        assertEquals(0, categorizer.calls)
        assertEquals(CategorizationStatus.PENDING, service.get("a").categorizationStatus)
    }

    @Test
    fun timeoutCountsAsFailedAttempt() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer {
            delay(10_000)
            CategorizationResult.Categories(listOf(1))
        }

        assertEquals(0, job(service, categorizer, timeoutMillis = 1_000L).recover())

        val updated = service.get("a")
        assertEquals(1, updated.categorizationAttempts)
        assertFalse(updated.categories.isNotEmpty())
    }

    @Test
    fun thrownExceptionCountsAsFailedAttempt() = runTest {
        val service = service(memory("a"))
        val categorizer = ScriptedCategorizer { throw IllegalStateException("crash") }

        assertEquals(0, job(service, categorizer).recover())

        assertEquals(1, service.get("a").categorizationAttempts)
    }

    @Test
    fun failedCategoryWriteDoesNotStopBatch() = runTest {
        val fake = service(memory("a"), memory("b"))
        val failing = FailingCategoryWrites(fake)
        val categorizer = ScriptedCategorizer { CategorizationResult.Categories(listOf(1)) }

        assertEquals(0, job(failing, categorizer).recover())

        assertEquals(2, categorizer.calls)
        assertEquals(0, fake.get("a").categorizationAttempts)
        assertTrue(fake.get("a").categories.isEmpty())
    }

    @Test
    fun failedAttemptWriteDoesNotStopBatch() = runTest {
        val fake = service(memory("a"), memory("b"))
        val failing = FailingCategoryWrites(fake)
        val categorizer = ScriptedCategorizer { CategorizationResult.Failed(IllegalStateException("crash")) }

        assertEquals(0, job(failing, categorizer).recover())

        assertEquals(2, categorizer.calls)
        assertEquals(0, fake.get("a").categorizationAttempts)
    }

    /** Delegates everything to [delegate] except the category writers, which always fail. */
    private class FailingCategoryWrites(private val delegate: FakeMemoryService) : MemoryService by delegate {
        override suspend fun recordCategorization(id: String, categories: List<Int>): Either<MemoryError, Unit> =
            MemoryError.Persistence(IllegalStateException("disk full")).left()

        override suspend fun recordCategorizationAttempt(id: String, empty: Boolean): Either<MemoryError, Unit> =
            MemoryError.Persistence(IllegalStateException("disk full")).left()
    }
}
