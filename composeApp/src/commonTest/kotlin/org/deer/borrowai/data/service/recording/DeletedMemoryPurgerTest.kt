package org.deer.borrowai.data.service.recording

import arrow.core.Either
import arrow.core.left
import kotlinx.coroutines.test.runTest
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.MemoryService

class DeletedMemoryPurgerTest {
    private val root = "/app/files".toPath()
    private val recordings = "/app/files/recordings".toPath()

    private fun memories() = FakeMemoryService(
        initialMemories = emptyList(),
        epochClock = EpochClock { 1_800_000_000_000L },
    )

    private suspend fun FakeMemoryService.createMemory(draft: MemoryDraft, id: String? = null): Memory =
        create(draft, id).getOrNull()!!

    private fun FakeFileSystem.addRecording(name: String) {
        createDirectories(recordings, mustCreate = false)
        write(recordings / name) { writeUtf8("audio") }
    }

    @Test
    fun deletedMemoryLosesAudioThenRecord() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc.m4a")
        val memories = memories()
        val memory = memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")
        memories.delete(memory.id)

        val purged = DeletedMemoryPurger(memories, root, { false }, fs).purge()

        assertEquals(1, purged)
        assertFalse(fs.exists(recordings / "abc.m4a"))
        assertTrue(memories.findById("abc").isLeft())
    }

    @Test
    fun activeMemoryIsNotTouched() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc.m4a")
        val memories = memories()
        memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")

        assertEquals(0, DeletedMemoryPurger(memories, root, { false }, fs).purge())
        assertTrue(fs.exists(recordings / "abc.m4a"))
        assertEquals(1, memories.getState().value.size)
    }

    @Test
    fun skipsWhileRecording() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc.m4a")
        val memories = memories()
        val memory = memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")
        memories.delete(memory.id)

        assertEquals(0, DeletedMemoryPurger(memories, root, { true }, fs).purge())
        assertTrue(fs.exists(recordings / "abc.m4a"))
        assertEquals(true, memories.findById("abc").getOrNull()?.deleted)
    }

    @Test
    fun sharedRecordingIsKeptWhileAnotherActiveMemoryUsesIt() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc.m4a")
        val memories = memories()
        memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "a")
        memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "b")
        memories.delete("a")

        assertEquals(1, DeletedMemoryPurger(memories, root, { false }, fs).purge())
        assertTrue(fs.exists(recordings / "abc.m4a"))
    }

    @Test
    fun failedListingDoesNotThrowAndRetriesLater() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc.m4a")
        val memories = memories()
        memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")
        memories.delete("abc")
        val failing = object : MemoryService by memories {
            var fail = true
            override suspend fun findAll(): Either<MemoryError, List<Memory>> {
                if (fail) return MemoryError.Persistence(IOException("db unavailable")).left()
                return memories.findAll()
            }
        }
        val purger = DeletedMemoryPurger(failing, root, { false }, fs)

        assertEquals(0, purger.purge())
        failing.fail = false
        assertEquals(1, purger.purge())
    }

    @Test
    fun deletedMemoryWithoutAudioIsStillPurged() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = memories.createMemory(MemoryDraft("t", "s", "x"), id = "text-only")
        memories.delete(memory.id)

        assertEquals(1, DeletedMemoryPurger(memories, root, { false }, fs).purge())
        assertTrue(memories.findById("text-only").isLeft())
    }

    @Test
    fun audioOrphanedByUpdateIsRemovedBeforeReconcileCanRecreateMemory() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc.m4a")
        val memories = memories()
        memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")
        memories.update("abc", MemoryDraft("edited", "", ""))
        memories.delete("abc")

        assertEquals(1, DeletedMemoryPurger(memories, root, { false }, fs).purge())
        assertFalse(fs.exists(recordings / "abc.m4a"))
        RecordingReconciler(
            memoryService = memories,
            recordingsRoot = root,
            isRecording = { false },
            epochClock = EpochClock { 1_800_000_000_000L },
            fileSystem = fs,
        ).reconcile()
        assertTrue(memories.findById("abc").isLeft())
    }

    @Test
    fun failedAudioRemovalKeepsRecordForRetry() = runTest {
        val base = FakeFileSystem()
        base.addRecording("abc.m4a")
        val fs = object : okio.ForwardingFileSystem(base) {
            override fun delete(path: okio.Path, mustExist: Boolean) = error("locked")
        }
        val memories = memories()
        val memory = memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")
        memories.delete(memory.id)

        assertEquals(0, DeletedMemoryPurger(memories, root, { false }, fs).purge())
        assertEquals(true, memories.findById("abc").getOrNull()?.deleted)
    }
}
