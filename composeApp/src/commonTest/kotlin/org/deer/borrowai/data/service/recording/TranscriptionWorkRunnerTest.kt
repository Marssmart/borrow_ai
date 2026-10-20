package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.MAX_TRANSCRIPTION_ATTEMPTS
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.service.FakeMemoryService

class TranscriptionWorkRunnerTest {
    private val root = "/root".toPath()

    private class CountingScheduler : TranscriptionScheduler {
        var transcriptionRequests = 0

        override fun enqueueTranscription() {
            transcriptionRequests++
        }

        override fun enqueueSweep() = Unit
    }

    private fun memories() = FakeMemoryService(epochClock = EpochClock { 1_800_000_000_000L })

    private suspend fun FakeMemoryService.addPending(id: String, status: TranscriptionStatus = TranscriptionStatus.PENDING) {
        create(
            MemoryDraft(title = "t", summary = "s", transcription = "", recordingPath = "recordings/$id.m4a"),
            id = id,
            transcriptionStatus = status,
        )
    }

    private suspend fun FakeMemoryService.get(id: String): Memory =
        findById(id).fold({ error("missing memory $id: $it") }, { it })

    private fun runner(
        memories: FakeMemoryService,
        transcribe: suspend (Path) -> String?,
        scheduler: TranscriptionScheduler = TranscriptionScheduler.None,
    ) = TranscriptionWorkRunner(
        memoryService = memories,
        recordingsRoot = root,
        batchTranscriber = object : BatchTranscriber {
            override suspend fun transcribe(recordingPath: Path): String? = transcribe(recordingPath)
        },
        scheduler = scheduler,
    )

    @Test
    fun drainTranscribesPendingMemoriesAndMarksThemDone() = runTest {
        val memories = memories()
        memories.addPending("a")
        memories.addPending("b")

        val drained = runner(memories, { path -> "text of ${path.name}" }).drain()

        assertTrue(drained)
        assertEquals("text of a.m4a", memories.get("a").transcription)
        assertEquals(TranscriptionStatus.DONE, memories.get("a").transcriptionStatus)
        assertEquals("text of b.m4a", memories.get("b").transcription)
    }

    @Test
    fun drainWithoutTranscriberAsksForRetryAndLeavesWorkPending() = runTest {
        val memories = memories()
        memories.addPending("a")

        val drained = TranscriptionWorkRunner(memories, root, batchTranscriber = null, scheduler = TranscriptionScheduler.None).drain()

        assertFalse(drained)
        assertEquals(TranscriptionStatus.PENDING, memories.get("a").transcriptionStatus)
        assertEquals(0, memories.get("a").transcriptionAttempts)
    }

    @Test
    fun unavailableModelDoesNotUseUpAttempts() = runTest {
        val memories = memories()
        memories.addPending("a")
        val unavailable = object : BatchTranscriber {
            override suspend fun isAvailable(): Boolean = false

            override suspend fun transcribe(recordingPath: Path): String? = error("must not transcribe")
        }

        val drained = TranscriptionWorkRunner(memories, root, unavailable, TranscriptionScheduler.None).drain()

        assertFalse(drained)
        assertEquals(TranscriptionStatus.PENDING, memories.get("a").transcriptionStatus)
        assertEquals(0, memories.get("a").transcriptionAttempts)
    }

    @Test
    fun repeatedFailuresStopAtAttemptCap() = runTest {
        val memories = memories()
        memories.addPending("a")

        val drained = runner(memories, { null }).drain()

        assertTrue(drained)
        val memory = memories.get("a")
        assertEquals(TranscriptionStatus.FAILED, memory.transcriptionStatus)
        assertEquals(MAX_TRANSCRIPTION_ATTEMPTS, memory.transcriptionAttempts)
    }

    @Test
    fun sweepRequeuesUnfinishedWorkAndSchedulesDrain() = runTest {
        val memories = memories()
        memories.addPending("stuck", TranscriptionStatus.IN_PROGRESS)
        val scheduler = CountingScheduler()

        val queued = runner(memories, { "x" }, scheduler).sweep()

        assertTrue(queued)
        assertEquals(TranscriptionStatus.PENDING, memories.get("stuck").transcriptionStatus)
        assertEquals(1, scheduler.transcriptionRequests)
    }
}
