package org.deer.borrowai.data.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MemoryAttemptsResetTest {
    private fun memory() = Memory(id = "m1", createdAtEpochMillis = 0L, title = "t", summary = "", transcription = "hello")

    @Test
    fun resetsGaveUpStages() {
        val reset = memory().copy(
            summaryStatus = SummaryStatus.GAVE_UP,
            summaryAttempts = 10,
            categorizationStatus = CategorizationStatus.GAVE_UP,
            categorizationAttempts = 3,
        ).withAttemptsReset()
        assertEquals(SummaryStatus.PENDING, reset.summaryStatus)
        assertEquals(0, reset.summaryAttempts)
        assertEquals(CategorizationStatus.PENDING, reset.categorizationStatus)
        assertEquals(0, reset.categorizationAttempts)
    }

    @Test
    fun keepsFinishedStagesAndDeletedMemories() {
        val done = memory().copy(
            summary = "s",
            summaryStatus = SummaryStatus.SUMMARIZED,
            categorizationStatus = CategorizationStatus.CATEGORIZED,
        )
        assertSame(done, done.withAttemptsReset())
        val deleted = memory().copy(deleted = true, categorizationStatus = CategorizationStatus.GAVE_UP)
        assertSame(deleted, deleted.withAttemptsReset())
    }
}
