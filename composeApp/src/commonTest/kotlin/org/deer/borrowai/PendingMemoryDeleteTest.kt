package org.deer.borrowai

import arrow.core.left
import arrow.core.right
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.MemoryError

class PendingMemoryDeleteTest {
    @Test
    fun requestHidesTheMemoryAndWritesNothingYet() {
        val step = PendingMemoryDelete().request("a", requestId = 1)

        assertEquals(setOf("a"), step.state.hiddenMemoryIds)
        assertEquals(UndoWindow("a", 1), step.state.undoWindow)
        assertNull(step.toWrite)
    }

    @Test
    fun undoInsideTheWindowClosesItWithoutWriting() {
        val requested = PendingMemoryDelete().request("a", requestId = 1).state

        val undone = requested.undo("a")

        assertNull(undone.undoWindow)
        assertTrue(undone.hiddenMemoryIds.isEmpty())
        // The window is gone, so the timer that would have committed it finds nothing to write.
        assertNull(undone.windowEnded(requestId = 1).toWrite)
    }

    @Test
    fun undoForAnotherMemoryIsIgnored() {
        val requested = PendingMemoryDelete().request("a", requestId = 1).state

        assertEquals(requested, requested.undo("b"))
    }

    @Test
    fun windowEndWritesThePendingMemoryOnce() {
        val requested = PendingMemoryDelete().request("a", requestId = 1).state

        val ended = requested.windowEnded(requestId = 1)

        assertEquals("a", ended.toWrite)
        assertNull(ended.state.undoWindow)
        assertEquals(setOf("a"), ended.state.hiddenMemoryIds)
        assertNull(ended.state.windowEnded(requestId = 1).toWrite)
    }

    @Test
    fun staleTimerFromAnEarlierWindowDoesNothing() {
        val first = PendingMemoryDelete().request("a", requestId = 1).state
        val undone = first.undo("a")
        val second = undone.request("a", requestId = 2).state

        assertNull(second.windowEnded(requestId = 1).toWrite)
        assertEquals("a", second.windowEnded(requestId = 2).toWrite)
    }

    @Test
    fun secondRequestCommitsTheFirstAtOnce() {
        val first = PendingMemoryDelete().request("a", requestId = 1).state

        val second = first.request("b", requestId = 2)

        assertEquals("a", second.toWrite)
        assertEquals(UndoWindow("b", 2), second.state.undoWindow)
        assertEquals(setOf("a", "b"), second.state.hiddenMemoryIds)
        assertEquals(setOf("a"), second.state.writing)
    }

    @Test
    fun requestForAHiddenMemoryIsIgnored() {
        val requested = PendingMemoryDelete().request("a", requestId = 1).state

        val again = requested.request("a", requestId = 2)

        assertEquals(requested, again.state)
        assertNull(again.toWrite)
    }

    @Test
    fun commitPendingWritesTheOpenWindowForLeavingTheTabOrBackgrounding() {
        val requested = PendingMemoryDelete().request("a", requestId = 1).state

        val committed = requested.commitPending()

        assertEquals("a", committed.toWrite)
        assertNull(committed.state.undoWindow)
        assertNull(committed.state.commitPending().toWrite)
    }

    @Test
    fun commitPendingWithoutAWindowDoesNothing() {
        val empty = PendingMemoryDelete()

        assertNull(empty.commitPending().toWrite)
        assertEquals(empty, empty.commitPending().state)
    }

    @Test
    fun finishedWriteUnhidesTheMemory() {
        val writing = PendingMemoryDelete().request("a", requestId = 1).state.commitPending().state

        val finished = writing.writeFinished("a")

        assertFalse("a" in finished.hiddenMemoryIds)
        assertTrue(finished.writing.isEmpty())
    }

    @Test
    fun notFoundCountsAsDeleted() {
        assertEquals(DeleteOutcome.DELETED, deleteOutcome(MemoryError.NotFound("a").left()))
    }

    @Test
    fun persistenceAndMalformedFailuresAreFailures() {
        assertEquals(
            DeleteOutcome.FAILED,
            deleteOutcome(MemoryError.Persistence(IllegalStateException("disk")).left()),
        )
        assertEquals(DeleteOutcome.FAILED, deleteOutcome(MemoryError.Malformed("a").left()))
    }

    @Test
    fun successIsDeleted() {
        assertEquals(DeleteOutcome.DELETED, deleteOutcome(Unit.right()))
    }
}
