package org.deer.borrowai.data.service

/** Failures of [MemoryService] operations. Returned on the Left side of an `Either`. */
sealed interface MemoryError {
    /** No memory with [id] exists, or it is not a memory document. */
    data class NotFound(val id: String) : MemoryError

    /** A stored document could not be read as a memory. Carries the document id. */
    data class Malformed(val id: String) : MemoryError

    /** The underlying database failed. */
    data class Persistence(val cause: Throwable) : MemoryError

    /** A caller-supplied value breaks a domain rule, such as a category name over its length limit. */
    data class InvalidInput(val reason: String) : MemoryError
}
