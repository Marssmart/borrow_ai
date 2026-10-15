package org.deer.borrowai.data.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutableCloseOnceTest {
    @Test
    fun startsOpen() {
        assertFalse(MutableCloseOnce().isClosed())
    }

    @Test
    fun runsActionOnlyOnFirstClose() {
        val closeOnce = MutableCloseOnce()
        var runs = 0

        assertTrue(closeOnce.closeOnce { runs++ })
        assertFalse(closeOnce.closeOnce { runs++ })

        assertEquals(1, runs)
        assertTrue(closeOnce.isClosed())
    }

    @Test
    fun failedActionStillMarksClosed() {
        val closeOnce = MutableCloseOnce()

        runCatching { closeOnce.closeOnce { error("boom") } }

        assertTrue(closeOnce.isClosed())
        assertFalse(closeOnce.closeOnce { error("should not run") })
    }
}
