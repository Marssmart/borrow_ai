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
import org.deer.borrowai.data.domain.MemoryDraft
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

    private fun saveRawMemory(id: String, recordingPath: String) {
        requireNotNull(database).save(
            MutableDocument(id).apply {
                setString("type", "memory")
                setLong("createdAtEpochMillis", 1_800_000_000_001L)
                setString("title", "Raw")
                setString("summary", "")
                setString("transcription", "")
                setString("recordingPath", recordingPath)
                setBoolean("deleted", false)
            },
        )
    }

    private fun <T> Either<MemoryError, T>.errorOrNull(): MemoryError? = (this as? Either.Left)?.value
}
