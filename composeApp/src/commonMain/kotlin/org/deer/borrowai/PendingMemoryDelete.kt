package org.deer.borrowai

import arrow.core.Either
import org.deer.borrowai.data.service.MemoryError

/** How long a deleted memory can be restored with Undo before its soft delete is written. */
internal const val MEMORY_UNDO_WINDOW_MILLIS = 10_000L

/**
 * The rules for deleting a memory from the list. A delete hides the memory at once, but the soft delete is written only
 * when its Undo window ends or a commit trigger fires. At most one memory waits in its window. Pure, so the rules are
 * tested without Compose or a database.
 */
internal data class PendingMemoryDelete(
    /** The memory waiting in its Undo window, if any. */
    val undoWindow: UndoWindow? = null,
    /** Memories whose soft delete has started but not returned. They stay hidden until the write finishes. */
    val writing: Set<String> = emptySet(),
) {
    /** Memories the list must not show. */
    val hiddenMemoryIds: Set<String> get() = writing + setOfNotNull(undoWindow?.memoryId)

    /**
     * Opens an Undo window for [memoryId]. A window that is already open is committed at once, so at most one stays open.
     * A memory that is already hidden is left alone.
     */
    fun request(memoryId: String, requestId: Long): Step {
        if (memoryId in hiddenMemoryIds) return Step(this, toWrite = null)
        val committed = commitPending()
        return Step(committed.state.copy(undoWindow = UndoWindow(memoryId, requestId)), toWrite = committed.toWrite)
    }

    /** Ends the window opened by [requestId]. A timer left over from an earlier window does nothing. */
    fun windowEnded(requestId: Long): Step =
        if (undoWindow?.requestId == requestId) commitPending() else Step(this, toWrite = null)

    /** Starts the write for the open window now. Used when the user leaves the Memories tab or the app goes to the background. */
    fun commitPending(): Step {
        val memoryId = undoWindow?.memoryId ?: return Step(this, toWrite = null)
        return Step(copy(undoWindow = null, writing = writing + memoryId), toWrite = memoryId)
    }

    /** Undo closes the window for [memoryId] and writes nothing. Undo for any other memory does nothing. */
    fun undo(memoryId: String): PendingMemoryDelete =
        if (undoWindow?.memoryId == memoryId) copy(undoWindow = null) else this

    /**
     * The write for [memoryId] has returned. The memory leaves [hiddenMemoryIds] whether or not it was deleted, so a
     * failed write puts it back on the list.
     */
    fun writeFinished(memoryId: String): PendingMemoryDelete = copy(writing = writing - memoryId)

    /** The next state, and the memory whose write must start now, if any. */
    data class Step(val state: PendingMemoryDelete, val toWrite: String?)
}

internal data class UndoWindow(val memoryId: String, val requestId: Long)

/** What the UI does once a delete write has returned. */
internal enum class DeleteOutcome {
    DELETED,
    FAILED,
}

/**
 * Maps the result of `MemoryService.delete`. NotFound means the memory is already gone, so the delete counts as done.
 * Persistence and Malformed failures leave the memory saved, and the UI shows an error.
 */
internal fun deleteOutcome(result: Either<MemoryError, Unit>): DeleteOutcome = result.fold(
    ifLeft = { error ->
        when (error) {
            is MemoryError.NotFound -> DeleteOutcome.DELETED
            is MemoryError.Persistence, is MemoryError.Malformed, is MemoryError.InvalidInput -> DeleteOutcome.FAILED
        }
    },
    ifRight = { DeleteOutcome.DELETED },
)
