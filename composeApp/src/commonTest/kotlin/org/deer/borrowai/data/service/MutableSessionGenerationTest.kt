package org.deer.borrowai.data.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutableSessionGenerationTest {
    @Test
    fun startsAtZeroAndIsCurrent() {
        val generation = MutableSessionGeneration()

        assertEquals(0L, generation.value)
        assertTrue(generation.isCurrent(0L))
        assertFalse(generation.isCurrent(1L))
    }

    @Test
    fun saveSetSucceedsOnlyWhenCurrentMatches() {
        val generation = MutableSessionGeneration()

        assertTrue(generation.safeSet(current = 0L, next = 5L))
        assertEquals(5L, generation.value)
        assertFalse(generation.safeSet(current = 0L, next = 9L))
        assertEquals(5L, generation.value)
    }

    @Test
    fun advanceIncrementsAndReturnsNewValue() {
        val generation = MutableSessionGeneration()

        assertEquals(1L, generation.advance())
        assertEquals(2L, generation.advance())
        assertTrue(generation.isCurrent(2L))
        assertFalse(generation.isCurrent(1L))
    }
}
