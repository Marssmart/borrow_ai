package org.deer.borrowai.data.service.summary

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MAX_SUMMARY_ATTEMPTS
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.SummaryStatus
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
        summarized: Boolean = false,
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
        summarized = summarized,
        summaryStatus = if (summarized) SummaryStatus.SUMMARIZED else SummaryStatus.PENDING,
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
        assertTrue(updated.summarized)
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
        assertFalse(service.get("a").summarized)

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
        assertFalse(service.get("a").summarized)
    }

    @Test
    fun failedAttemptIncrementsCounterByOne() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Failed(RuntimeException("boom")) }

        job(service, summarizer).recover()

        assertEquals(1, service.get("a").summaryAttempts)
        assertFalse(service.get("a").summarized)
    }

    @Test
    fun unavailableSummarizerLeavesMemoryUntouched() = runTest {
        val service = service(memory("a"))
        val summarizer = ScriptedSummarizer { SummaryResult.Unavailable }

        assertEquals(0, job(service, summarizer).recover())

        val unchanged = service.get("a")
        assertEquals(0, unchanged.summaryAttempts)
        assertFalse(unchanged.summarized)
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
            memory("summarized", summary = "done", summarized = true),
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
        assertFalse(service.get("a").summarized)
    }

    @Test
    fun blankTranscriptAlreadyDoneIsSkippedWithoutGivingUp() = runTest {
        val service = service(memory("a", transcription = "", recordingPath = "a.m4a").copy(transcriptionDone = true))
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
}
