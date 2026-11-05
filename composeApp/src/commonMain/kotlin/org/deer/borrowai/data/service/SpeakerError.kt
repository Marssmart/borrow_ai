package org.deer.borrowai.data.service

/** Failures of [SpeakerService] operations. Returned on the Left side of an `Either`. */
sealed interface SpeakerError {
    /** No speaker, occurrence, or embedding with [id] exists. */
    data class NotFound(val id: String) : SpeakerError

    /** A stored speaker document could not be read as its record type. Carries the document id. */
    data class Malformed(val id: String) : SpeakerError

    /** The underlying database failed. */
    data class Persistence(val cause: Throwable) : SpeakerError

    /** A caller-supplied value breaks a rule, such as a blank name or an empty embedding. */
    data class InvalidInput(val reason: String) : SpeakerError

    /** Another speaker already uses [name], compared without regard to case. */
    data class NameTaken(val name: String) : SpeakerError

    /** Speaker analysis is off, so its result is not stored. Turning the feature off stops analysis (AC-6.3). */
    data object Disabled : SpeakerError

    /** Speaker analysis cannot be enabled before the current consent text has been accepted. */
    data object ConsentRequired : SpeakerError
}
