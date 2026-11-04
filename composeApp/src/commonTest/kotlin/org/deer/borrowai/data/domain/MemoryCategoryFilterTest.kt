package org.deer.borrowai.data.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryCategoryFilterTest {
    @Test
    fun countsEachCategoryByTheMemoriesThatUseIt() {
        val memories = listOf(
            memory("a", categories = listOf(1)),
            memory("b", categories = listOf(1, 2)),
            memory("c", categories = emptyList()),
        )

        assertEquals(mapOf(1 to 2, 2 to 1), categoriesInUse(memories))
    }

    @Test
    fun effectiveFilterDropsSelectedCategoriesNoLongerInUse() {
        assertEquals(setOf(1), effectiveCategoryFilter(selected = setOf(1, 5), inUse = setOf(1, 2)))
        assertEquals(emptySet(), effectiveCategoryFilter(selected = setOf(5), inUse = setOf(1, 2)))
    }

    @Test
    fun aMemoryMatchesWhenItHasAnySelectedCategory() {
        val memory = memory("a", categories = listOf(2, 3))

        assertTrue(memory.hasAnyCategory(setOf(1, 3)))
        assertFalse(memory.hasAnyCategory(setOf(1)))
    }

    @Test
    fun anEmptySelectionMatchesEveryMemory() {
        assertTrue(memory("a", categories = emptyList()).hasAnyCategory(emptySet()))
    }

    private fun memory(id: String, categories: List<Int>): Memory = Memory(
        id = id,
        createdAtEpochMillis = 1L,
        title = "",
        summary = "",
        transcription = "Some text",
        categories = categories,
    )
}
