package org.deer.borrowai.data.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryCategorizationStatusTest {
    private fun memory(
        categories: List<Int> = emptyList(),
        status: CategorizationStatus = CategorizationStatus.PENDING,
        attempts: Int = 0,
    ) = Memory(
        id = "m1",
        createdAtEpochMillis = 0L,
        title = "t",
        summary = "",
        transcription = "hello",
        categories = categories,
        categorizationStatus = status,
        categorizationAttempts = attempts,
    )

    @Test
    fun withCategoriesMarksMemoryCategorized() {
        val categorized = memory().withCategories(listOf(2, 5))
        assertEquals(listOf(2, 5), categorized.categories)
        assertEquals(CategorizationStatus.CATEGORIZED, categorized.categorizationStatus)
    }

    @Test
    fun finishedAttemptWithTextStaysPendingUntilTheLimit() {
        val first = memory().afterFinishedCategorizationAttempt(empty = false)
        assertEquals(1, first.categorizationAttempts)
        assertEquals(CategorizationStatus.PENDING, first.categorizationStatus)
        assertTrue(first.isCategorizationRetryable)
    }

    @Test
    fun finishedAttemptOnBlankTranscriptionMarksEmpty() {
        val empty = memory().afterFinishedCategorizationAttempt(empty = true)
        assertEquals(1, empty.categorizationAttempts)
        assertEquals(CategorizationStatus.EMPTY, empty.categorizationStatus)
    }

    @Test
    fun reachingTheAttemptLimitGivesUpEvenWhenEmpty() {
        val lastAttempt = memory(attempts = MAX_CATEGORIZATION_ATTEMPTS - 1)
            .afterFinishedCategorizationAttempt(empty = true)
        assertEquals(MAX_CATEGORIZATION_ATTEMPTS, lastAttempt.categorizationAttempts)
        assertEquals(CategorizationStatus.GAVE_UP, lastAttempt.categorizationStatus)
        assertFalse(lastAttempt.isCategorizationRetryable)
    }

    @Test
    fun giveUpSetsLimitAndStatus() {
        val gaveUp = memory(attempts = 1).withCategorizationGaveUp()
        assertEquals(MAX_CATEGORIZATION_ATTEMPTS, gaveUp.categorizationAttempts)
        assertEquals(CategorizationStatus.GAVE_UP, gaveUp.categorizationStatus)
    }

    @Test
    fun preTaggedDraftStatusFollowsItsCategories() {
        assertEquals(CategorizationStatus.CATEGORIZED, categorizationStatusFor(listOf(3)))
        assertEquals(CategorizationStatus.PENDING, categorizationStatusFor(emptyList()))
    }

    @Test
    fun unchangedUserCategoriesKeepStatusAndAttempts() {
        val original = memory(categories = listOf(1), status = CategorizationStatus.GAVE_UP, attempts = 3)
        assertEquals(original, original.withUserCategories(listOf(1)))
    }

    @Test
    fun changedUserCategoriesResetAttemptsAndMarkCategorized() {
        val changed = memory(categories = listOf(1), status = CategorizationStatus.GAVE_UP, attempts = 3)
            .withUserCategories(listOf(2))
        assertEquals(listOf(2), changed.categories)
        assertEquals(CategorizationStatus.CATEGORIZED, changed.categorizationStatus)
        assertEquals(0, changed.categorizationAttempts)
    }

    @Test
    fun clearingUserCategoriesMakesMemoryPendingAgain() {
        val cleared = memory(categories = listOf(1), status = CategorizationStatus.CATEGORIZED)
            .withUserCategories(emptyList())
        assertEquals(CategorizationStatus.PENDING, cleared.categorizationStatus)
        assertTrue(cleared.isCategorizationRetryable)
    }
}
