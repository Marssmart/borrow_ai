package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.service.FakeMemoryService

class RecordingReconcilerTest {
    private val root = "/app/files".toPath()
    private val recordings = "/app/files/recordings".toPath()

    private fun memories() = FakeMemoryService(
        initialMemories = emptyList(),
        epochClock = EpochClock { 1_800_000_000_000L },
    )

    private fun reconciler(
        memories: FakeMemoryService,
        fs: FakeFileSystem,
        isRecording: () -> Boolean = { false },
        epochClock: EpochClock = EpochClock { 1_800_000_000_000L },
    ) = RecordingReconciler(
        memoryService = memories,
        recordingsRoot = root,
        isRecording = isRecording,
        epochClock = epochClock,
        fileSystem = fs,
    )

    private fun FakeFileSystem.addRecording(id: String) {
        createDirectories(recordings, mustCreate = false)
        write(recordings / "$id.m4a") { writeUtf8("audio") }
    }

    @Test
    fun orphanedRecordingGetsMemoryWithMatchingId() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc")
        val memories = memories()

        assertEquals(1, reconciler(memories, fs).reconcile())

        val memory = memories.findById("abc")
        assertEquals("recordings/abc.m4a", memory?.recordingPath)
    }

    @Test
    fun recordingWithExistingMemoryIsNotDuplicated() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc")
        val memories = memories()
        memories.create(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")

        assertEquals(0, reconciler(memories, fs).reconcile())
        assertEquals(1, memories.getState().value.size)
    }

    @Test
    fun memoryMatchedByRecordingPathIsNotDuplicated() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc")
        val memories = memories()
        memories.create(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "other")

        assertEquals(0, reconciler(memories, fs).reconcile())
        assertEquals(1, memories.getState().value.size)
    }

    @Test
    fun deletedMemoryIsNotRecreatedFromItsRecording() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc")
        val memories = memories()
        val memory = memories.create(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")
        memories.delete(memory.id)

        assertEquals(0, reconciler(memories, fs).reconcile())
        assertEquals(true, memories.findById("abc")?.deleted)
    }

    @Test
    fun temporaryRecordingIsNotImported() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("temp_abc")
        val memories = memories()

        assertEquals(0, reconciler(memories, fs).reconcile())
        assertNull(memories.findById("temp_abc"))
    }

    @Test
    fun sweepSkipsWhileRecording() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc")
        val memories = memories()

        assertEquals(0, reconciler(memories, fs, isRecording = { true }).reconcile())
        assertNull(memories.findById("abc"))
    }

    @Test
    fun missingRecordingsFolderIsNoOp() = runTest {
        val memories = memories()

        assertEquals(0, reconciler(memories, FakeFileSystem()).reconcile())
        assertEquals(0, memories.getState().value.size)
    }

    @Test
    fun failedCreateLeavesFileForNextSweep() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc")
        val failing = object : org.deer.borrowai.data.service.MemoryService by memories() {
            override suspend fun create(draft: MemoryDraft, id: String?): Memory = error("disk full")
        }
        val reconciler = RecordingReconciler(
            memoryService = failing,
            recordingsRoot = root,
            isRecording = { false },
            epochClock = EpochClock { 1_800_000_000_000L },
            fileSystem = fs,
        )

        assertEquals(0, reconciler.reconcile())
        assertEquals(true, fs.exists(recordings / "abc.m4a"))
    }

    @Test
    fun oldTemporaryCaptureIsDeletedAndRecentOneIsKept() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(recordings)
        fs.write(recordings / "temp_old.m4a") { writeUtf8("partial") }
        fs.write(recordings / "temp_new.m4a") { writeUtf8("partial") }

        // Ages are measured against the clock, so a far-future clock makes every file stale.
        reconciler(memories(), fs, epochClock = EpochClock { Long.MAX_VALUE / 2 }).reconcile()
        assertFalse(fs.exists(recordings / "temp_old.m4a"))

        fs.write(recordings / "temp_new.m4a") { writeUtf8("partial") }
        reconciler(memories(), fs, epochClock = EpochClock { 0L }).reconcile()
        assertTrue(fs.exists(recordings / "temp_new.m4a"))
    }

    @Test
    fun temporaryCaptureIsKeptWhileRecording() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(recordings)
        fs.write(recordings / "temp_active.m4a") { writeUtf8("partial") }

        reconciler(memories(), fs, isRecording = { true }, epochClock = EpochClock { Long.MAX_VALUE / 2 }).reconcile()

        assertTrue(fs.exists(recordings / "temp_active.m4a"))
    }
}
