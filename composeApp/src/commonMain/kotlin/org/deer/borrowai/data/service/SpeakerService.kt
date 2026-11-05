package org.deer.borrowai.data.service

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import org.deer.borrowai.data.domain.MAX_EMBEDDINGS_PER_SPEAKER
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerOccurrenceLink
import org.deer.borrowai.data.domain.SpeakerSummary

const val SPEAKER_CONSENT_TEXT_VERSION = 1

/**
 * The speaker library and the occurrences found in memories. Speaker data is kept apart from memories. Speaker
 * analysis changes a memory only through its speaker status fields.
 */
interface SpeakerService {
    /** Emits after each committed change, so readers can reload. */
    val changes: Flow<Unit>

    /** Whether speaker analysis is on. It is off until the user opts in, so a missing setting reads as off. */
    suspend fun isFeatureEnabled(): Either<SpeakerError, Boolean>

    /** Whether the user accepted the current consent text. */
    suspend fun hasCurrentConsent(): Either<SpeakerError, Boolean>

    /** Records the current consent text version and timestamp, then enables speaker analysis. */
    suspend fun acceptConsent(): Either<SpeakerError, Unit>

    /** Turns speaker analysis on or off. Turning it off deletes nothing. */
    suspend fun setFeatureEnabled(enabled: Boolean): Either<SpeakerError, Unit>

    /** Every library speaker, most recently used first. */
    suspend fun findSpeakers(): Either<SpeakerError, List<Speaker>>

    /** Returns the speaker with [id], or [SpeakerError.NotFound]. */
    suspend fun findSpeaker(id: String): Either<SpeakerError, Speaker>

    /** Adds a speaker named [name]. The name is trimmed and must be unique, ignoring case. */
    suspend fun createSpeaker(name: String): Either<SpeakerError, Speaker>

    /** Renames the speaker [id] under the same rule as [createSpeaker]. */
    suspend fun renameSpeaker(id: String, name: String): Either<SpeakerError, Unit>

    /**
     * One-pass linked-memory counts and last-heard times for the library. [memoryCreatedAtEpochMillis] supplies active
     * memory creation times keyed by memory id, so last-heard reflects the newest linked memory rather than a speaker
     * update or analysis time.
     */
    suspend fun findSpeakerSummaries(
        memoryCreatedAtEpochMillis: Map<String, Long>,
    ): Either<SpeakerError, Map<String, SpeakerSummary>>

    /** The occurrences linked to [speakerId], newest memory ids first when their store order is available. */
    suspend fun findOccurrencesForSpeaker(speakerId: String): Either<SpeakerError, List<SpeakerOccurrence>>

    /**
     * Assigns one occurrence to [speakerId], or clears it when null. A non-null assignment is always manual and
     * updates its embedding, rejection history, speaker cap/count, and last-used time in the same local transaction.
     */
    suspend fun assignOccurrence(occurrenceId: String, speakerId: String?): Either<SpeakerError, Unit>

    /** Clears an automatic link and records it as rejected so later analysis does not restore it immediately. */
    suspend fun rejectAutomaticMatch(occurrenceId: String): Either<SpeakerError, Unit>

    /** Restores [previous] only while the occurrence still matches [expected], for a persisted Undo action. */
    suspend fun restoreOccurrenceLink(
        expected: SpeakerOccurrenceLink,
        previous: SpeakerOccurrenceLink,
    ): Either<SpeakerError, Unit>

    /** Moves source identity links and eligible voiceprints to the target, then removes source atomically. */
    suspend fun mergeSpeakers(sourceId: String, targetId: String): Either<SpeakerError, Unit>

    /** Deletes the speaker [id]. Its voiceprints are deleted with it. Its occurrences become unknown. Memories are not touched. */
    suspend fun deleteSpeaker(id: String): Either<SpeakerError, Unit>

    /**
     * Removes embeddings and occurrences whose memory is not in [liveMemoryIds]. Used by the purger after its pass, so
     * rows left by an interrupted purge are not kept forever. Returns how many rows were removed.
     */
    suspend fun deleteRowsWithoutMemory(liveMemoryIds: Set<String>): Either<SpeakerError, Int>

    /** Embeddings attached to a speaker and stored with [modelVersion]. Unknown embeddings are left out. */
    suspend fun findSpeakerEmbeddings(modelVersion: Int): Either<SpeakerError, List<SpeakerEmbedding>>

    /** The occurrences of memory [memoryId], ordered by ordinal. */
    suspend fun findOccurrences(memoryId: String): Either<SpeakerError, List<SpeakerOccurrence>>

    /** The occurrences of every memory in one read. The memory list uses it for its chips, so it never reads per row (AC-1.5). */
    suspend fun findAllOccurrences(): Either<SpeakerError, List<SpeakerOccurrence>>

    /**
     * Replaces every occurrence and embedding of memory [memoryId] with [drafts], in one batch. A linked draft also adds
     * its embedding to its speaker, and a speaker keeps at most [MAX_EMBEDDINGS_PER_SPEAKER]. The oldest are dropped.
     * Returns [SpeakerError.NotFound] when a draft names a speaker that does not exist, and [SpeakerError.Disabled]
     * while speaker analysis is off, so a withdrawal cannot be followed by new voiceprints.
     */
    suspend fun replaceOccurrences(
        memoryId: String,
        modelVersion: Int,
        drafts: List<OccurrenceDraft>,
    ): Either<SpeakerError, Unit>

    /** Removes every occurrence and embedding of [memoryId], including embeddings attached to a speaker (AC-6.4). */
    suspend fun deleteForMemory(memoryId: String): Either<SpeakerError, Unit>

    /** Removes all speaker data and turns speaker analysis off. This is the withdrawal of consent (AC-6.6). */
    suspend fun clearAll(): Either<SpeakerError, Unit>
}
