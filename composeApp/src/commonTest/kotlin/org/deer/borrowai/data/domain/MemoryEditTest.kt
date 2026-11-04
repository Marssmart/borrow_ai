package org.deer.borrowai.data.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryEditTest {
    @Test
    fun titleAtTheLimitIsAcceptedAndOneMoreIsRefused() {
        assertNull(memoryEditViolation(edit(title = "a".repeat(MEMORY_TITLE_MAX_LENGTH)), TranscriptionStatus.DONE))
        assertEquals(
            "A title can be at most 80 characters.",
            memoryEditViolation(edit(title = "a".repeat(MEMORY_TITLE_MAX_LENGTH + 1)), TranscriptionStatus.DONE),
        )
    }

    @Test
    fun summaryAtTheLimitIsAcceptedAndOneMoreIsRefused() {
        assertNull(memoryEditViolation(edit(summary = "a".repeat(4_000)), TranscriptionStatus.DONE))
        assertEquals(
            "A summary can be at most 4000 characters.",
            memoryEditViolation(edit(summary = "a".repeat(4_001)), TranscriptionStatus.DONE),
        )
    }

    @Test
    fun blankTranscriptIsRefusedWhenItIsDoneOrFailed() {
        assertEquals("The transcript can't be empty.", memoryEditViolation(edit(transcription = " "), TranscriptionStatus.DONE))
        assertEquals("The transcript can't be empty.", memoryEditViolation(edit(transcription = ""), TranscriptionStatus.FAILED))
    }

    @Test
    fun transcriptRulesDoNotApplyWhileItIsBeingCreated() {
        assertNull(memoryEditViolation(edit(transcription = ""), TranscriptionStatus.PENDING))
        assertNull(memoryEditViolation(edit(transcription = ""), TranscriptionStatus.IN_PROGRESS))
    }

    @Test
    fun transcriptAtTheLimitIsAcceptedAndOneMoreIsRefused() {
        assertNull(memoryEditViolation(edit(transcription = "a".repeat(50_000)), TranscriptionStatus.DONE))
        assertEquals(
            "A transcript can be at most 50000 characters.",
            memoryEditViolation(edit(transcription = "a".repeat(50_001)), TranscriptionStatus.DONE),
        )
    }

    @Test
    fun tooManyCategoriesAreRefusedWithTheCategoryRule() {
        assertEquals(
            categoryIdsViolation(listOf(1, 2, 3, 4)),
            memoryEditViolation(edit(categoryIds = listOf(1, 2, 3, 4)), TranscriptionStatus.DONE),
        )
    }

    @Test
    fun changedSummarySetsTheMarkerAndCountsAsDone() {
        val updated = memory(summary = "old", summaryStatus = SummaryStatus.PENDING)
            .withEdit(edit(summary = "new"))

        assertEquals("new", updated.summary)
        assertTrue(updated.summaryEditedByUser)
        assertEquals(SummaryStatus.SUMMARIZED, updated.summaryStatus)
    }

    @Test
    fun unchangedSummaryKeepsTheMarkerOff() {
        val updated = memory(summary = "same").withEdit(edit(summary = "same"))

        assertFalse(updated.summaryEditedByUser)
    }

    @Test
    fun blankSummaryClearsTheMarkerAndRequestsANewOne() {
        val updated = memory(
            summary = "mine",
            summaryStatus = SummaryStatus.SUMMARIZED,
            summaryEditedByUser = true,
            summaryAttempts = 3,
        ).withEdit(edit(summary = ""))

        assertFalse(updated.summaryEditedByUser)
        assertEquals(SummaryStatus.PENDING, updated.summaryStatus)
        assertEquals(0, updated.summaryAttempts)
    }

    @Test
    fun changedCategoriesSetTheMarker() {
        val updated = memory(categories = listOf(1)).withEdit(edit(categoryIds = listOf(2)))

        assertEquals(listOf(2), updated.categories)
        assertTrue(updated.categoriesEditedByUser)
    }

    @Test
    fun unchangedCategoriesKeepTheMarkerOff() {
        val updated = memory(categories = listOf(1)).withEdit(edit(categoryIds = listOf(1)))

        assertFalse(updated.categoriesEditedByUser)
    }

    @Test
    fun changedTranscriptKeepsTheSummaryAndCategories() {
        val updated = memory(
            transcription = "old",
            transcriptionStatus = TranscriptionStatus.FAILED,
            summary = "summary",
            summaryStatus = SummaryStatus.SUMMARIZED,
            categories = listOf(1),
        ).withEdit(edit(transcription = "typed", categoryIds = listOf(1), summary = "summary"))

        assertEquals("typed", updated.transcription)
        assertEquals(TranscriptionStatus.DONE, updated.transcriptionStatus)
        assertEquals("summary", updated.summary)
        assertFalse(updated.summaryEditedByUser)
        assertEquals(listOf(1), updated.categories)
        assertFalse(updated.categoriesEditedByUser)
    }

    @Test
    fun userSummaryIsNotRetryable() {
        val memory = memory(summaryStatus = SummaryStatus.PENDING, summaryEditedByUser = true)

        assertFalse(memory.isSummaryRetryable)
    }

    @Test
    fun userCategoriesAreNotRetryable() {
        val memory = memory(categorizationStatus = CategorizationStatus.PENDING, categoriesEditedByUser = true)

        assertFalse(memory.isCategorizationRetryable)
    }

    @Test
    fun guardLeavesAUserSummaryUnchanged() {
        val mine = memory(summary = "mine", summaryEditedByUser = true)

        assertEquals(mine, mine.unlessSummaryEdited { it.withSummary("ai") })
    }

    @Test
    fun guardAppliesTheChangeWhenTheUserDidNotWriteTheSummary() {
        val ai = memory(summary = "", summaryStatus = SummaryStatus.PENDING)

        assertEquals("ai", ai.unlessSummaryEdited { it.withSummary("ai") }.summary)
    }

    @Test
    fun guardLeavesUserCategoriesUnchanged() {
        val mine = memory(categories = listOf(2), categoriesEditedByUser = true)

        assertEquals(mine, mine.unlessCategoriesEdited { it.withCategories(listOf(5)) })
    }

    @Test
    fun requestingANewSummaryClearsTheMarkerAndResetsAttempts() {
        val requested = memory(summaryEditedByUser = true, summaryStatus = SummaryStatus.GAVE_UP, summaryAttempts = 10)
            .withSummaryRequested()

        assertFalse(requested.summaryEditedByUser)
        assertEquals(SummaryStatus.PENDING, requested.summaryStatus)
        assertEquals(0, requested.summaryAttempts)
    }

    @Test
    fun requestingNewCategoriesClearsTheMarkerAndResetsAttempts() {
        val requested = memory(categoriesEditedByUser = true, categorizationAttempts = 10).withCategorizationRequested()

        assertFalse(requested.categoriesEditedByUser)
        assertEquals(CategorizationStatus.PENDING, requested.categorizationStatus)
        assertEquals(0, requested.categorizationAttempts)
    }

    private fun edit(
        title: String = "",
        summary: String = "",
        transcription: String = "text",
        categoryIds: List<Int> = emptyList(),
    ) = MemoryEdit(title = title, summary = summary, transcription = transcription, categoryIds = categoryIds)

    private fun memory(
        summary: String = "",
        summaryStatus: SummaryStatus = SummaryStatus.PENDING,
        summaryAttempts: Int = 0,
        summaryEditedByUser: Boolean = false,
        transcription: String = "text",
        transcriptionStatus: TranscriptionStatus = TranscriptionStatus.DONE,
        categories: List<Int> = emptyList(),
        categorizationStatus: CategorizationStatus = CategorizationStatus.PENDING,
        categorizationAttempts: Int = 0,
        categoriesEditedByUser: Boolean = false,
    ) = Memory(
        id = "m",
        createdAtEpochMillis = 0L,
        title = "",
        summary = summary,
        transcription = transcription,
        summaryStatus = summaryStatus,
        summaryAttempts = summaryAttempts,
        summaryEditedByUser = summaryEditedByUser,
        transcriptionStatus = transcriptionStatus,
        categories = categories,
        categorizationStatus = categorizationStatus,
        categorizationAttempts = categorizationAttempts,
        categoriesEditedByUser = categoriesEditedByUser,
    )
}
