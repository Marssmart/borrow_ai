package org.deer.borrowai.data.service.kotbase

import kotbase.Database
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MemoryDraft

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
    )

    @Test
    fun createPersistsAndPublishesMemory() = runTest {
        val service = openService()
        val created = service.create(
            MemoryDraft("First note", "Short summary", "Full transcription", "recordings/a.m4a"),
        )

        assertEquals("memory-1", created.id)
        assertEquals(1_800_000_000_000L, created.createdAtEpochMillis)
        assertEquals(created, service.findById(created.id))
        assertEquals(listOf(created), service.getState().value)
    }

    @Test
    fun blankTitleAndNullRecordingPathRoundTrip() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("", "", ""))

        val found = service.findById(created.id)
        assertEquals("", found?.title)
        assertNull(found?.recordingPath)
    }

    @Test
    fun updateReplacesFieldsAndKeepsCreationTime() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("Old", "Old summary", "Old text"))

        val updated = service.update(
            created.id,
            MemoryDraft("New", "New summary", "New text", "recordings/b.m4a"),
        )

        assertEquals("New", updated?.title)
        assertEquals("recordings/b.m4a", updated?.recordingPath)
        assertEquals(created.createdAtEpochMillis, updated?.createdAtEpochMillis)
        assertEquals(updated, service.findById(created.id))
        assertEquals(listOf(updated), service.getState().value)
    }

    @Test
    fun updateMissingIdReturnsNull() = runTest {
        val service = openService()

        assertNull(service.update("missing", MemoryDraft("t", "s", "x")))
        assertTrue(service.getState().value.isEmpty())
    }

    @Test
    fun deleteRemovesMemoryAndReportsResult() = runTest {
        val service = openService()
        val created = service.create(MemoryDraft("To delete", "", ""))

        assertTrue(service.delete(created.id))
        assertEquals(true, service.findById(created.id)?.deleted)
        assertTrue(service.getState().value.isEmpty())
        assertFalse(service.delete(created.id))
        assertTrue(service.purge(created.id))
        assertNull(service.findById(created.id))
    }

    @Test
    fun memoriesAreOrderedNewestFirst() = runTest {
        val times = mutableListOf(1_000L, 3_000L, 2_000L)
        val ids = mutableListOf("memory-1", "memory-2", "memory-3")
        val service = KotbaseMemoryService.open(
            database = requireNotNull(database),
            epochClock = EpochClock { times.removeAt(0) },
            idGenerator = { ids.removeAt(0) },
        )

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
        val created = first.create(MemoryDraft("Persisted", "s", "t", "recordings/c.m4a"))

        requireNotNull(database).close()
        database = Database(databaseName)
        val reopened = openService()

        assertEquals(listOf(created), reopened.getState().value)
    }
}
