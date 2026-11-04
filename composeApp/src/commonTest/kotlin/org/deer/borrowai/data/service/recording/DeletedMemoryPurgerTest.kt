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
import org.deer.borrowai.data.domain.MemoryEdit
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.SPEAKER_ANALYSIS_VERSION
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.FakeSpeakerService
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.SpeakerError
import org.deer.borrowai.data.service.SpeakerService

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

        val purged = DeletedMemoryPurger(memories, root, { false }, fs, FakeSpeakerService()).purge()

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

        assertEquals(0, DeletedMemoryPurger(memories, root, { false }, fs, FakeSpeakerService()).purge())
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

        assertEquals(0, DeletedMemoryPurger(memories, root, { true }, fs, FakeSpeakerService()).purge())
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

        assertEquals(1, DeletedMemoryPurger(memories, root, { false }, fs, FakeSpeakerService()).purge())
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
        val purger = DeletedMemoryPurger(failing, root, { false }, fs, FakeSpeakerService())

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

        assertEquals(1, DeletedMemoryPurger(memories, root, { false }, fs, FakeSpeakerService()).purge())
        assertTrue(memories.findById("text-only").isLeft())
    }

    @Test
    fun audioOfEditedMemoryIsRemovedBeforeReconcileCanRecreateMemory() = runTest {
        val fs = FakeFileSystem()
        fs.addRecording("abc.m4a")
        val memories = memories()
        memories.createMemory(MemoryDraft("t", "s", "x", "recordings/abc.m4a"), id = "abc")
        memories.edit("abc", MemoryEdit("edited", "", "x", emptyList()))
        memories.delete("abc")

        assertEquals(1, DeletedMemoryPurger(memories, root, { false }, fs, FakeSpeakerService()).purge())
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

        assertEquals(0, DeletedMemoryPurger(memories, root, { false }, fs, FakeSpeakerService()).purge())
        assertEquals(true, memories.findById("abc").getOrNull()?.deleted)
    }

    @Test
    fun speakerRowsAreRemovedBeforeTheRecord() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.createMemory(MemoryDraft("t", "s", "x"), id = "abc")
        speakers.replaceOccurrences(
            memoryId = "abc",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(
                OccurrenceDraft(
                    ordinal = 1,
                    speakerId = null,
                    link = SpeakerLink.NONE,
                    totalSpeechMs = 2_000L,
                    sampleStartMs = 0L,
                    sampleEndMs = 2_000L,
                    centroid = floatArrayOf(1f, 0f),
                ),
            ),
        )
        memories.delete("abc")
        val steps = mutableListOf<String>()
        val recordingSpeakers = object : SpeakerService by speakers {
            override suspend fun deleteForMemory(memoryId: String): Either<SpeakerError, Unit> {
                steps += "speakers"
                return speakers.deleteForMemory(memoryId)
            }
        }
        val recordingMemories = object : MemoryService by memories {
            override suspend fun purge(id: String): Boolean {
                steps += "record"
                return memories.purge(id)
            }
        }

        assertEquals(1, DeletedMemoryPurger(recordingMemories, root, { false }, fs, recordingSpeakers).purge())
        assertEquals(listOf("speakers", "record"), steps)
        assertTrue(speakers.findOccurrences("abc").getOrNull()!!.isEmpty())
        assertTrue(memories.findById("abc").isLeft())
    }

    @Test
    fun failedSpeakerCleanupKeepsTheRecordForRetry() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.createMemory(MemoryDraft("t", "s", "x"), id = "abc")
        memories.delete("abc")
        speakers.failDeletes = true
        val purger = DeletedMemoryPurger(memories, root, { false }, fs, speakers)

        assertEquals(0, purger.purge())
        assertEquals(true, memories.findById("abc").getOrNull()?.deleted)

        speakers.failDeletes = false
        assertEquals(1, purger.purge())
    }

    @Test
    fun speakerRowsWithoutAMemoryAreRemovedByThePurge() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        speakers.replaceOccurrences(
            memoryId = "gone",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(
                OccurrenceDraft(
                    ordinal = 1,
                    speakerId = null,
                    link = SpeakerLink.NONE,
                    totalSpeechMs = 2_000L,
                    sampleStartMs = 0L,
                    sampleEndMs = 2_000L,
                    centroid = floatArrayOf(1f, 0f),
                ),
            ),
        )

        DeletedMemoryPurger(memories, root, { false }, fs, speakers).purge()

        assertTrue(speakers.findOccurrences("gone").getOrNull()!!.isEmpty())
        assertTrue(speakers.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!.isEmpty())
    }
}
