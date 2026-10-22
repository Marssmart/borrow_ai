package org.deer.borrowai.data.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemorySummaryStatusTest {
    private fun memory(
        summary: String = "",
        status: SummaryStatus = SummaryStatus.PENDING,
        attempts: Int = 0,
    ) = Memory(
        id = "m1",
        createdAtEpochMillis = 0L,
        title = "t",
        summary = summary,
        transcription = "hello",
        summaryStatus = status,
        summaryAttempts = attempts,
    )

    @Test
    fun createStatusFollowsNonBlankSummary() {
        assertEquals(SummaryStatus.SUMMARIZED, summaryStatusFor("A summary"))
        assertEquals(SummaryStatus.PENDING, summaryStatusFor(""))
        assertEquals(SummaryStatus.PENDING, summaryStatusFor("   "))
    }

    @Test
    fun nonBlankEditMarksMemorySummarized() {
        val edited = memory(status = SummaryStatus.PENDING).withEditedSummary("New text")
        assertEquals("New text", edited.summary)
        assertEquals(SummaryStatus.SUMMARIZED, edited.summaryStatus)
        assertTrue(edited.summarized)
    }

    @Test
    fun clearingSummarizedMemoryResetsToPendingWithFreshAttempts() {
        val cleared = memory(summary = "Old", status = SummaryStatus.SUMMARIZED, attempts = 4)
            .withEditedSummary("")
        assertEquals("", cleared.summary)
        assertEquals(SummaryStatus.PENDING, cleared.summaryStatus)
        assertEquals(0, cleared.summaryAttempts)
        assertFalse(cleared.summarized)
    }

    @Test
    fun blankEditToNonSummarizedMemoryKeepsStatusAndAttempts() {
        val edited = memory(status = SummaryStatus.GAVE_UP, attempts = MAX_SUMMARY_ATTEMPTS)
            .withEditedSummary("")
        assertEquals(SummaryStatus.GAVE_UP, edited.summaryStatus)
        assertEquals(MAX_SUMMARY_ATTEMPTS, edited.summaryAttempts)
    }
}
