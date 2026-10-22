package org.deer.borrowai.data.service.summary

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MAX_SUMMARY_ATTEMPTS
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.SummaryStatus
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.service.FakeMemoryService

class MemorySummaryRecoveryJobTest {
    private class ScriptedSummarizer(
        private val result: suspend (String) -> SummaryResult,
    ) : MemorySummarizer {
        var calls = 0

        override suspend fun summarize(transcription: String): SummaryResult {
            calls++
            return result(transcription)
        }
    }

    private fun memory(
        id: String,
        transcription: String = "hello there",
        recordingPath: String? = "$id.m4a",
        summary: String = "",
        summaryStatus: SummaryStatus = if (summary.isNotBlank()) SummaryStatus.SUMMARIZED else SummaryStatus.PENDING,
        summaryAttempts: Int = 0,
        deleted: Boolean = false,
    ) = Memory(
        id = id,
        createdAtEpochMillis = 1_800_000_000_000L,
        title = "Title $id",
        summary = summary,
        transcription = transcription,
        recordingPath = recordingPath,
        deleted = deleted,
        summaryStatus = summaryStatus,
        summaryAttempts = summaryAttempts,
    )

    private fun service(vararg memories: Memory) = FakeMemoryService(
        initialMemories = memories.toList(),
        epochClock = EpochClock { 1_800_000_000_000L },
    )

    private fun job(
        service: FakeMemoryService,
        summarizer: MemorySummarizer,
        isRecording: () -> Boolean = { false },
        timeoutMillis: Long = 60_000L,
    ) = MemorySummaryRecoveryJob(
        memoryService = service,
        summarizer = summarizer,
        isRecording = isRecording,
        timeoutMillis = timeoutMillis,
    )

    private suspend fun FakeMemoryService.get(id: String): Memory = findById(id).getOrNull()!!

    @Test
    fun storesSummaryAndMarksMemorySummarized() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Summary("A short summary") }

        val stored = job(service, summarizer).recover()

        assertEquals(1, stored)
        val updated = service.get("a")
        assertEquals("A short summary", updated.summary)
        assertEquals(SummaryStatus.SUMMARIZED, updated.summaryStatus)
        assertEquals(0, updated.summaryAttempts)
    }

    @Test
    fun emptyResultIsCountedAndRetriedUntilLimit() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Empty }
        val recovery = job(service, summarizer)

        assertEquals(0, recovery.recover())
        assertEquals(1, service.get("a").summaryAttempts)
        assertEquals(SummaryStatus.EMPTY, service.get("a").summaryStatus)
        assertEquals(false, service.get("a").summaryStatus == SummaryStatus.SUMMARIZED)

        repeat(MAX_SUMMARY_ATTEMPTS) { recovery.recover() }
        assertEquals(MAX_SUMMARY_ATTEMPTS, service.get("a").summaryAttempts)
        assertEquals(MAX_SUMMARY_ATTEMPTS, summarizer.calls, "Counted empty attempts stop at the limit")
    }

    @Test
    fun blankSummaryTextIsCountedAsEmpty() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Summary("   ") }

        job(service, summarizer).recover()

        assertEquals(1, service.get("a").summaryAttempts)
        assertEquals(SummaryStatus.EMPTY, service.get("a").summaryStatus)
        assertEquals(false, service.get("a").summaryStatus == SummaryStatus.SUMMARIZED)
    }

    @Test
    fun failedAttemptIncrementsCounterByOne() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Failed(RuntimeException("boom")) }

        job(service, summarizer).recover()

        assertEquals(1, service.get("a").summaryAttempts)
        assertEquals(SummaryStatus.PENDING, service.get("a").summaryStatus)
    }

    @Test
    fun unavailableSummarizerLeavesMemoryUntouched() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Unavailable }

        assertEquals(0, job(service, summarizer).recover())

        val unchanged = service.get("a")
        assertEquals(0, unchanged.summaryAttempts)
        assertEquals(SummaryStatus.PENDING, unchanged.summaryStatus)
    }

    @Test
    fun blankTranscriptIsSkippedWithoutCallingSummarizerOrCounting() = runTest {
        val service = service(memory("a", transcription = "", recordingPath = "a.m4a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Summary("unused") }

        val stored = job(service, summarizer).recover()

        assertEquals(0, stored)
        assertEquals(0, summarizer.calls)
        assertEquals(0, service.get("a").summaryAttempts)
        assertEquals(null, service.get("a").lastTranscriptionError)
    }

    @Test
    fun blankTranscriptWithoutRecordingIsSkippedWithoutCounting() = runTest {
        val service = service(memory("a", transcription = "", recordingPath = null))
        val summarizer = ScriptedSummarizer { SummaryResult.Summary("unused") }

        job(service, summarizer).recover()

        assertEquals(0, summarizer.calls)
        assertEquals(0, service.get("a").summaryAttempts)
    }

    @Test
    fun ineligibleMemoriesAreSkipped() = runTest {
        val service = service(
            memory("deleted", deleted = true),
            memory("summarized", summary = "done", summaryStatus = SummaryStatus.SUMMARIZED),
            memory("exhausted", summaryAttempts = MAX_SUMMARY_ATTEMPTS),
        )
        val summarizer = ScriptedSummarizer { SummaryResult.Summary("new") }

        assertEquals(0, job(service, summarizer).recover())
        assertEquals(0, summarizer.calls)
    }

    @Test
    fun attemptsStopAtLimitAcrossRuns() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Failed(RuntimeException("boom")) }
        val recovery = job(service, summarizer)

        repeat(MAX_SUMMARY_ATTEMPTS + 5) { recovery.recover() }

        assertEquals(MAX_SUMMARY_ATTEMPTS, service.get("a").summaryAttempts)
        assertEquals(MAX_SUMMARY_ATTEMPTS, summarizer.calls)
    }

    @Test
    fun skipsEverythingWhileRecording() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Summary("new") }

        assertEquals(0, job(service, summarizer, isRecording = { true }).recover())

        assertEquals(0, summarizer.calls)
        assertEquals(0, service.get("a").summaryAttempts)
    }

    @Test
    fun timeoutCountsAsFailedAttempt() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer {
            delay(10_000)
            SummaryResult.Summary("too late")
        }

        job(service, summarizer, timeoutMillis = 1_000L).recover()

        assertEquals(1, service.get("a").summaryAttempts)
        assertEquals(SummaryStatus.PENDING, service.get("a").summaryStatus)
    }

    @Test
    fun blankTranscriptAlreadyDoneIsSkippedWithoutGivingUp() = runTest {
        val service = service(memory("a", transcription = "", recordingPath = "a.m4a").copy(transcriptionStatus = TranscriptionStatus.DONE))
        val summarizer = ScriptedSummarizer { SummaryResult.Summary("unused") }

        job(service, summarizer).recover()

        assertEquals(0, summarizer.calls)
        assertEquals(SummaryStatus.PENDING, service.get("a").summaryStatus)
        assertEquals(0, service.get("a").summaryAttempts)
    }

    @Test
    fun failureStoresExceptionClassAndMessage() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Failed(IllegalStateException("model missing")) }

        job(service, summarizer).recover()

        assertEquals("IllegalStateException: model missing", service.get("a").lastTranscriptionError)
    }

    @Test
    fun cancellationWritesNothingAndIsNotCounted() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { throw kotlinx.coroutines.CancellationException("stop") }

        val thrown = runCatching { job(service, summarizer).recover() }.exceptionOrNull()

        assertTrue(thrown is kotlinx.coroutines.CancellationException)
        assertEquals(0, service.get("a").summaryAttempts)
    }

    @Test
    fun thrownExceptionCountsAsFailedAttempt() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { error("engine crashed") }

        job(service, summarizer).recover()

        assertEquals(1, service.get("a").summaryAttempts)
    }

    @Test
    fun missingModelStopsRunAfterFirstCallAndDoesNotCount() = runTest {
        val service = service(memory("a"), memory("b"), memory("c"))
        val summarizer = ScriptedSummarizer { SummaryResult.ModelMissing }
        val recovery = job(service, summarizer)

        assertEquals(0, recovery.recover())

        assertEquals(1, summarizer.calls, "A missing model is not retried for every memory in the same run")
        assertTrue(recovery.summaryModelMissing.value)
        listOf("a", "b", "c").forEach { id ->
            val unchanged = service.get(id)
            assertEquals(0, unchanged.summaryAttempts)
            assertEquals(SummaryStatus.PENDING, unchanged.summaryStatus)
        }
    }

    @Test
    fun missingModelFlagClearsWhenModelBecomesAvailable() = runTest {
        val service = service(memory("a"))
        var modelPresent = false
        val summarizer = ScriptedSummarizer {
            if (modelPresent) SummaryResult.Summary("A short summary") else SummaryResult.ModelMissing
        }
        val recovery = job(service, summarizer)

        recovery.recover()
        assertTrue(recovery.summaryModelMissing.value)

        modelPresent = true
        assertEquals(1, recovery.recover())
        assertFalse(recovery.summaryModelMissing.value)
        assertEquals(SummaryStatus.SUMMARIZED, service.get("a").summaryStatus)
    }

    @Test
    fun modelMissingFlagIsNotClearedByAnotherRunStillInProgress() = runTest {
        val service = service(memory("a"))
        val gate = CompletableDeferred<Unit>()
        var firstCall = true
        val summarizer = ScriptedSummarizer {
            if (firstCall) {
                firstCall = false
                SummaryResult.ModelMissing
            } else {
                gate.await()
                SummaryResult.ModelMissing
            }
        }
        val recovery = job(service, summarizer)

        recovery.recover()
        assertTrue(recovery.summaryModelMissing.value)

        val overlapping = async { recovery.recover() }
        runCurrent()
        assertEquals(2, summarizer.calls)
        assertTrue(recovery.summaryModelMissing.value, "A run still in progress must not clear the notice")

        gate.complete(Unit)
        overlapping.await()
        assertTrue(recovery.summaryModelMissing.value)
    }

    @Test
    fun modelMissingFlagStaysFalseWithoutEligibleMemories() = runTest {
        val service = service(memory("summarized", summary = "done", summaryStatus = SummaryStatus.SUMMARIZED))
        val summarizer = ScriptedSummarizer { SummaryResult.ModelMissing }
        val recovery = job(service, summarizer)

        recovery.recover()

        assertEquals(0, summarizer.calls)
        assertFalse(recovery.summaryModelMissing.value)
    }
}
