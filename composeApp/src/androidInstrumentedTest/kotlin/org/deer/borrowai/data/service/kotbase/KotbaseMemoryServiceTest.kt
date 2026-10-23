package org.deer.borrowai.data.service.kotbase

import arrow.core.Either
import kotbase.Database
import kotbase.MutableDocument
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MAX_SUMMARY_ATTEMPTS
import org.deer.borrowai.data.domain.MAX_TRANSCRIPTION_ATTEMPTS
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.SummaryStatus
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.service.MemoryError

class KotbaseMemoryServiceTest {
    private var database: Database? = null
    private val databaseName = "memory-test-${System.nanoTime()}"

    @BeforeTest
    fun setUp() {
        database = Database(databaseName)
    }

    @AfterTest
    fun tearDown() {
        database?.delete()
        database = null
    }

    private suspend fun openService(
        now: Long = 1_800_000_000_000L,
        ids: MutableList<String> = mutableListOf("memory-1", "memory-2", "memory-3"),
    ): KotbaseMemoryService = KotbaseMemoryService.open(
        database = requireNotNull(database),
        epochClock = EpochClock { now },
        idGenerator = { ids.removeAt(0) },
    ).getOrNull()!!

    @Test
    fun createPersistsAndPublishesMemory() = runTest {
        val service = openService()
        val created = service.create(
            MemoryDraft("First note", "Short summary", "Full transcription", "recordings/a.m4a"),
        ).getOrNull()!!

        assertEquals("memory-1", created.id)
        assertEquals(1_800_000_000_000L, created.createdAtEpochMillis)
        assertEquals(created, service.findById(created.id).getOrNull())
        assertEquals(listOf(created), service.getState().value)
    }

    @Test
    fun blankTitleAndNullRecordingPathRoundTrip() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("", "", "")).getOrNull()!!

        val found = service.findById(created.id).getOrNull()
        assertEquals("", found?.title)
        assertEquals(null, found?.recordingPath)
    }

    @Test
    fun updateReplacesFieldsAndKeepsCreationTime() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("Old", "Old summary", "Old text")).getOrNull()!!

        assertTrue(
            service.update(created.id, MemoryDraft("New", "New summary", "New text", "recordings/b.m4a")).isRight(),
        )

        val updated = service.findById(created.id).getOrNull()
        assertEquals("New", updated?.title)
        assertEquals("recordings/b.m4a", updated?.recordingPath)
        assertEquals(created.createdAtEpochMillis, updated?.createdAtEpochMillis)
        assertEquals(listOf(updated), service.getState().value)
    }

    @Test
    fun updateMissingIdReturnsLeft() = runTest {
        val service = openService()

        assertTrue(service.update("missing", MemoryDraft("t", "s", "x")).isLeft())
        assertTrue(service.getState().value.isEmpty())
    }

    @Test
    fun deleteRemovesMemoryAndReportsResult() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("To delete", "", "")).getOrNull()!!

        assertTrue(service.delete(created.id).isRight())
        assertEquals(true, service.findById(created.id).getOrNull()?.deleted)
        assertTrue(service.getState().value.isEmpty())
        assertTrue(service.delete(created.id).isLeft())
        assertTrue(service.purge(created.id))
        assertTrue(service.findById(created.id).isLeft())
    }

    @Test
    fun memoriesAreOrderedNewestFirst() = runTest {
        val times = mutableListOf(1_000L, 3_000L, 2_000L)
        val ids = mutableListOf("memory-1", "memory-2", "memory-3")
        val service = KotbaseMemoryService.open(
            database = requireNotNull(database),
            epochClock = EpochClock { times.removeAt(0) },
            idGenerator = { ids.removeAt(0) },
        ).getOrNull()!!

        service.create(MemoryDraft("oldest", "", ""))
        service.create(MemoryDraft("newest", "", ""))
        service.create(MemoryDraft("middle", "", ""))

        assertEquals(
            listOf("newest", "middle", "oldest"),
            service.getState().value.map { it.title },
        )
    }

    @Test
    fun reopeningLoadsPersistedMemories() = runTest {
        val first = openService()
        val created = first.create(MemoryDraft("Persisted", "s", "t", "recordings/c.m4a")).getOrNull()!!

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        assertEquals(listOf(created), reopened.getState().value)
        assertFalse(reopened.getState().value.isEmpty())
    }

    @Test
    fun storedMemoryWithInvalidRecordingPathIsMalformedOnEveryRead() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("Valid", "", "")).getOrNull()!!
        saveRawMemory(id = "bad-1", recordingPath = "/absolute/recording.m4a")

        assertEquals(MemoryError.Malformed("bad-1"), service.findById("bad-1").errorOrNull())
        assertEquals(
            MemoryError.Malformed("bad-1"),
            service.update("bad-1", MemoryDraft("t", "s", "x")).errorOrNull(),
        )
        assertEquals(MemoryError.Malformed("bad-1"), service.delete("bad-1").errorOrNull())
        assertEquals(MemoryError.Malformed("bad-1"), service.findAll().errorOrNull())
        assertEquals(listOf(created), service.getState().value)
    }

    @Test
    fun closedDatabaseReportsPersistenceFailureOnWriteAndRead() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("Before close", "", "")).getOrNull()!!
        val closing = requireNotNull(database)
        closing.close()

        val createFailure = service.create(MemoryDraft("After close", "", "")).errorOrNull()
        val findFailure = service.findById(created.id).errorOrNull()
        database = Database(databaseName)

        assertIs<MemoryError.Persistence>(createFailure)
        assertIs<MemoryError.Persistence>(findFailure)
    }

    @Test
    fun legacyRecordingWithoutTranscriptIsRecoveredAsPendingAfterReopen() = runTest {
        openService()
        saveRawMemory(id = "legacy-1", recordingPath = "recordings/legacy.m4a")

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        val recovered = reopened.findById("legacy-1").getOrNull()!!
        assertEquals(TranscriptionStatus.PENDING, recovered.transcriptionStatus)
        assertEquals("legacy-1", reopened.claimNextPendingTranscription().getOrNull()?.id)
    }

    @Test
    fun failedTranscriptionStatusAndReasonPersistAcrossReopen() = runTest {
        val first = openService()
        val created = first.create(
            MemoryDraft("Failing", "", "", "recordings/f.m4a"),
            transcriptionStatus = TranscriptionStatus.PENDING,
        ).getOrNull()!!
        repeat(MAX_TRANSCRIPTION_ATTEMPTS) {
            first.claimNextPendingTranscription()
            first.failTranscription(created.id, "IllegalStateException: boom")
        }

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        val found = reopened.findById(created.id).getOrNull()!!
        assertEquals(TranscriptionStatus.FAILED, found.transcriptionStatus)
        assertEquals("IllegalStateException: boom", found.lastTranscriptionError)
    }

    @Test
    fun categoriesRoundTripAndReplaceOnUpdate() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("Tagged", "", "", categories = listOf(2, 5, 9))).getOrNull()!!

        assertEquals(listOf(2, 5, 9), service.findById(created.id).getOrNull()?.categories)

        service.update(created.id, MemoryDraft("Tagged", "", "", categories = listOf(7)))
        assertEquals(listOf(7), service.findById(created.id).getOrNull()?.categories)

        service.update(created.id, MemoryDraft("Tagged", "", "", categories = emptyList()))
        assertEquals(emptyList(), service.findById(created.id).getOrNull()?.categories)
    }

    @Test
    fun categoriesSurviveReopen() = runTest {
        val first = openService()
        val created = first.create(MemoryDraft("Reopen", "", "", categories = listOf(1, 3))).getOrNull()!!

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        assertEquals(listOf(1, 3), reopened.findById(created.id).getOrNull()?.categories)
    }

    @Test
    fun legacyMemoryWithoutCategoriesKeyReadsAsEmpty() = runTest {
        openService()
        saveRawMemory(id = "legacy-cat", recordingPath = "recordings/legacy-cat.m4a")

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        assertEquals(emptyList(), reopened.findById("legacy-cat").getOrNull()?.categories)
    }

    @Test
    fun storedRowOverWriteLimitReadsEveryCategoryId() = runTest {
        openService()
        saveRawMemory(id = "wide", recordingPath = "recordings/wide.m4a", categories = "1|2|3|4")

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        assertEquals(listOf(1, 2, 3, 4), reopened.findById("wide").getOrNull()?.categories)
    }

    @Test
    fun danglingCategoryIdIsKeptAsStored() = runTest {
        openService()
        saveRawMemory(id = "dangling", recordingPath = "recordings/dangling.m4a", categories = "42|7")

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        assertEquals(listOf(42, 7), reopened.findById("dangling").getOrNull()?.categories)
    }

    @Test
    fun createCategoryAssignsSequentialIdsFromOne() = runTest {
        val service = openService()
        assertEquals(emptyList(), service.findCategories().getOrNull())

        val first = service.createCategory("Work", "Job related").getOrNull()!!
        val second = service.createCategory("Health", "Body and mind").getOrNull()!!

        assertEquals(1, first.id)
        assertEquals(2, second.id)
        assertEquals(listOf(first, second), service.findCategories().getOrNull())
    }

    @Test
    fun createCategoryRejectsOverlongName() = runTest {
        val service = openService()

        val failure = service.createCategory("A".repeat(16), "ok").errorOrNull()

        assertIs<MemoryError.InvalidInput>(failure)
        assertEquals(emptyList(), service.findCategories().getOrNull())
    }

    @Test
    fun categoryRowsAreExcludedFromMemoryLists() = runTest {
        val service = openService()
        service.createCategory("Work", "Job related")
        val created = service.create(MemoryDraft("Only memory", "", "")).getOrNull()!!

        assertEquals(listOf(created), service.getState().value)
        assertEquals(listOf(created), service.findAll().getOrNull())
    }

    @Test
    fun recordSummaryPersistsSummarizedStatusAcrossReopen() = runTest {
        val first = openService()
        val created = first.create(MemoryDraft("Note", "", "Transcript", "recordings/s.m4a")).getOrNull()!!

        assertEquals(Either.Right(Unit), first.recordSummary(created.id, "Short summary"))

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        val found = reopened.findById(created.id).getOrNull()!!
        assertEquals("Short summary", found.summary)
        assertEquals(SummaryStatus.SUMMARIZED, found.summaryStatus)
        assertTrue(found.summarized)
        assertEquals(listOf(found), reopened.getState().value)
    }

    @Test
    fun giveUpSummaryPersistsGaveUpStatusAcrossReopen() = runTest {
        val first = openService()
        val created = first.create(MemoryDraft("Note", "", "Transcript", "recordings/g.m4a")).getOrNull()!!

        assertEquals(Either.Right(Unit), first.giveUpSummary(created.id))

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        val found = reopened.findById(created.id).getOrNull()!!
        assertEquals(SummaryStatus.GAVE_UP, found.summaryStatus)
        assertEquals(MAX_SUMMARY_ATTEMPTS, found.summaryAttempts)
        assertFalse(found.summarized)
    }

    @Test
    fun recordFinishedAttemptKeepsPendingStatusAndErrorAcrossReopen() = runTest {
        val first = openService()
        val created = first.create(MemoryDraft("Note", "", "Transcript", "recordings/f.m4a")).getOrNull()!!

        assertEquals(Either.Right(Unit), first.recordFinishedAttempt(created.id, "IllegalStateException: boom"))

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        val found = reopened.findById(created.id).getOrNull()!!
        assertEquals(SummaryStatus.PENDING, found.summaryStatus)
        assertEquals(1, found.summaryAttempts)
        assertEquals("IllegalStateException: boom", found.lastTranscriptionError)
    }

    @Test
    fun summaryUpdateOnMissingMemoryReturnsNotFound() = runTest {
        val service = openService()

        assertEquals(MemoryError.NotFound("missing"), service.recordSummary("missing", "x").errorOrNull())
        assertEquals(MemoryError.NotFound("missing"), service.giveUpSummary("missing").errorOrNull())
        assertEquals(MemoryError.NotFound("missing"), service.recordEmpty("missing").errorOrNull())
    }

    @Test
    fun summaryUpdateOnDeletedMemoryReturnsNotFound() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("Gone", "", "", "recordings/d.m4a")).getOrNull()!!
        assertEquals(Either.Right(Unit), service.delete(created.id))

        assertEquals(MemoryError.NotFound(created.id), service.recordSummary(created.id, "x").errorOrNull())
        assertEquals(MemoryError.NotFound(created.id), service.giveUpSummary(created.id).errorOrNull())
    }

    @Test
    fun summaryUpdateOnMalformedMemoryReturnsMalformed() = runTest {
        val service = openService()
        saveRawMemory(id = "bad-2", recordingPath = "/absolute/recording.m4a")

        assertEquals(MemoryError.Malformed("bad-2"), service.recordSummary("bad-2", "x").errorOrNull())
        assertEquals(MemoryError.Malformed("bad-2"), service.giveUpSummary("bad-2").errorOrNull())
    }

    @Test
    fun updateCategoriesPreservesSummarizedStatusAcrossReopen() = runTest {
        val first = openService()
        val created = first.create(MemoryDraft("Note", "", "Transcript", "recordings/c1.m4a")).getOrNull()!!
        assertEquals(Either.Right(Unit), first.recordSummary(created.id, "Short summary"))

        assertEquals(Either.Right(Unit), first.updateCategories(created.id, listOf(4)))

        requireNotNull(database).close()
        database = Database(databaseName)
        val found = openService().findById(created.id).getOrNull()!!
        assertEquals(listOf(4), found.categories)
        assertEquals("Short summary", found.summary)
        assertEquals(SummaryStatus.SUMMARIZED, found.summaryStatus)
    }

    @Test
    fun updateCategoriesPreservesGaveUpStatusAndAttemptsAcrossReopen() = runTest {
        val first = openService()
        val created = first.create(MemoryDraft("Note", "", "Transcript", "recordings/c2.m4a")).getOrNull()!!
        assertEquals(Either.Right(Unit), first.giveUpSummary(created.id))

        assertEquals(Either.Right(Unit), first.updateCategories(created.id, listOf(2, 3)))

        requireNotNull(database).close()
        database = Database(databaseName)
        val found = openService().findById(created.id).getOrNull()!!
        assertEquals(listOf(2, 3), found.categories)
        assertEquals(SummaryStatus.GAVE_UP, found.summaryStatus)
        assertEquals(MAX_SUMMARY_ATTEMPTS, found.summaryAttempts)
    }

    @Test
    fun updateCategoriesRejectsInvalidIdsAndKeepsStoredCategories() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("Tagged", "", "", categories = listOf(6))).getOrNull()!!

        assertIs<MemoryError.InvalidInput>(service.updateCategories(created.id, listOf(1, 1)).errorOrNull())
        assertIs<MemoryError.InvalidInput>(service.updateCategories(created.id, listOf(0)).errorOrNull())
        assertIs<MemoryError.InvalidInput>(service.updateCategories(created.id, listOf(1, 2, 3, 4)).errorOrNull())

        assertEquals(listOf(6), service.findById(created.id).getOrNull()?.categories)
    }

    @Test
    fun updateCategoriesOnMissingMemoryReturnsNotFound() = runTest {
        val service = openService()

        assertEquals(MemoryError.NotFound("missing"), service.updateCategories("missing", listOf(1)).errorOrNull())
    }

    @Test
    fun updateCategoriesOnDeletedMemoryReturnsNotFound() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("Gone", "", "", "recordings/c3.m4a")).getOrNull()!!
        assertEquals(Either.Right(Unit), service.delete(created.id))

        assertEquals(MemoryError.NotFound(created.id), service.updateCategories(created.id, listOf(1)).errorOrNull())
    }

    @Test
    fun updateCategoriesOnMalformedMemoryReturnsMalformed() = runTest {
        val service = openService()
        saveRawMemory(id = "bad-3", recordingPath = "/absolute/recording.m4a")

        assertEquals(MemoryError.Malformed("bad-3"), service.updateCategories("bad-3", listOf(1)).errorOrNull())
    }

    private fun saveRawMemory(id: String, recordingPath: String, categories: String? = null) {
        requireNotNull(database).save(
            MutableDocument(id).apply {
                setString("type", "memory")
                setLong("createdAtEpochMillis", 1_800_000_000_001L)
                setString("title", "Raw")
                setString("summary", "")
                setString("transcription", "")
                setString("recordingPath", recordingPath)
                setBoolean("deleted", false)
                if (categories != null) setString("categories", categories)
            },
        )
    }

    private fun <T> Either<MemoryError, T>.errorOrNull(): MemoryError? = (this as? Either.Left)?.value
}
