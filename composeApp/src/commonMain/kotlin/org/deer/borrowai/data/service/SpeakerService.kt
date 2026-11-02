package org.deer.borrowai.data.service

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import org.deer.borrowai.data.domain.MAX_EMBEDDINGS_PER_SPEAKER
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerOccurrence

/**
 * The speaker library and the occurrences found in memories. Speaker data is kept apart from memories. Speaker
 * analysis changes a memory only through its speaker status fields.
 */
interface SpeakerService {
    /** Emits after each committed change, so readers can reload. */
    val changes: Flow<Unit>

    /** Whether speaker analysis is on. It is off until the user opts in, so a missing setting reads as off. */
    suspend fun isFeatureEnabled(): Either<SpeakerError, Boolean>

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

    /** Deletes the speaker [id]. Its voiceprints are deleted with it. Its occurrences become unknown. Memories are not touched. */
    suspend fun deleteSpeaker(id: String): Either<SpeakerError, Unit>

    /** Embeddings attached to a speaker and stored with [modelVersion]. Unknown embeddings are left out. */
    suspend fun findSpeakerEmbeddings(modelVersion: Int): Either<SpeakerError, List<SpeakerEmbedding>>

    /** The occurrences of memory [memoryId], ordered by ordinal. */
    suspend fun findOccurrences(memoryId: String): Either<SpeakerError, List<SpeakerOccurrence>>

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
