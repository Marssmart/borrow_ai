package org.deer.borrowai.data.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MAX_EMBEDDINGS_PER_SPEAKER
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence

/** In-memory [SpeakerService] that follows the same rules as the Kotbase store, for tests that need no database. */
class FakeSpeakerService(
    enabled: Boolean = false,
    private val epochClock: EpochClock = EpochClock { 1_800_000_000_000L },
    /** When true, [replaceOccurrences] fails, so a test can check that a failed analysis keeps no rows. */
    var failWrites: Boolean = false,
) : SpeakerService {
    private val mutex = Mutex()
    private var featureEnabled = enabled
    private val speakers = linkedMapOf<String, Speaker>()
    private val embeddings = linkedMapOf<String, SpeakerEmbedding>()
    private val occurrences = linkedMapOf<String, SpeakerOccurrence>()
    private var generated = 0
    private val events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** When true, [findSpeakerEmbeddings] fails, so a test can check that a failed read uses no attempt. */
    var failReads = false

    /** When true, [deleteForMemory] fails, so a test can check that the purger keeps the memory record. */
    var failDeletes = false

    override val changes: Flow<Unit> = events.asSharedFlow()

    override suspend fun isFeatureEnabled(): Either<SpeakerError, Boolean> = mutex.withLock {
        featureEnabled.right()
    }

    override suspend fun setFeatureEnabled(enabled: Boolean): Either<SpeakerError, Unit> =
        mutex.withLock<Either<SpeakerError, Unit>> {
            featureEnabled = enabled
            published()
            Unit.right()
        }

    override suspend fun findSpeakers(): Either<SpeakerError, List<Speaker>> = mutex.withLock {
        speakers.values.sortedByDescending { it.lastUsedAtEpochMillis }.right()
    }

    override suspend fun findSpeaker(id: String): Either<SpeakerError, Speaker> = mutex.withLock {
        speakers[id]?.right() ?: SpeakerError.NotFound(id).left()
    }

    override suspend fun createSpeaker(name: String): Either<SpeakerError, Speaker> =
        mutex.withLock<Either<SpeakerError, Speaker>> {
            val trimmed = name.trim()
            when {
                trimmed.isEmpty() -> SpeakerError.InvalidInput("A speaker name cannot be blank.").left()
                speakers.values.any { it.name.equals(trimmed, ignoreCase = true) } ->
                    SpeakerError.NameTaken(trimmed).left()
                else -> {
                    val now = epochClock.nowEpochMillis()
                    val speaker = Speaker(nextId(), trimmed, now, now, 0)
                    speakers[speaker.id] = speaker
                    published()
                    speaker.right()
                }
            }
        }

    override suspend fun renameSpeaker(id: String, name: String): Either<SpeakerError, Unit> =
        mutex.withLock<Either<SpeakerError, Unit>> {
            val existing = speakers[id] ?: return@withLock SpeakerError.NotFound(id).left()
            val trimmed = name.trim()
            when {
                trimmed.isEmpty() -> SpeakerError.InvalidInput("A speaker name cannot be blank.").left()
                speakers.values.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) } ->
                    SpeakerError.NameTaken(trimmed).left()
                else -> {
                    speakers[id] = existing.copy(name = trimmed)
                    published()
                    Unit.right()
                }
            }
        }

    override suspend fun deleteSpeaker(id: String): Either<SpeakerError, Unit> =
        mutex.withLock<Either<SpeakerError, Unit>> {
            if (speakers.remove(id) == null) return@withLock SpeakerError.NotFound(id).left()
            for (occurrence in occurrences.values.toList()) {
                if (occurrence.speakerId == id) {
                    occurrences[occurrence.id] = occurrence.copy(speakerId = null, link = SpeakerLink.NONE)
                }
            }
            // Voiceprints go with their speaker.
            for (embedding in embeddings.values.toList()) {
                if (embedding.speakerId == id) embeddings.remove(embedding.id)
            }
            published()
            Unit.right()
        }

    override suspend fun findSpeakerEmbeddings(modelVersion: Int): Either<SpeakerError, List<SpeakerEmbedding>> =
        mutex.withLock<Either<SpeakerError, List<SpeakerEmbedding>>> {
            if (failReads) {
                return@withLock SpeakerError.Persistence(IllegalStateException("speaker store unavailable")).left()
            }
            embeddings.values.filter { it.speakerId != null && it.modelVersion == modelVersion }.right()
        }

    override suspend fun findOccurrences(memoryId: String): Either<SpeakerError, List<SpeakerOccurrence>> =
        mutex.withLock {
            occurrences.values.filter { it.memoryId == memoryId }.sortedBy { it.ordinal }.right()
        }

    override suspend fun replaceOccurrences(
        memoryId: String,
        modelVersion: Int,
        drafts: List<OccurrenceDraft>,
    ): Either<SpeakerError, Unit> = mutex.withLock<Either<SpeakerError, Unit>> {
        if (!featureEnabled) return@withLock SpeakerError.Disabled.left()
        if (failWrites) {
            return@withLock SpeakerError.Persistence(IllegalStateException("speaker store unavailable")).left()
        }
        val missing = drafts.mapNotNull { it.speakerId }.firstOrNull { it !in speakers }
        if (missing != null) return@withLock SpeakerError.NotFound(missing).left()
        removeRows(memoryId)
        val now = epochClock.nowEpochMillis()
        for (draft in drafts) {
            val occurrenceId = nextId()
            val embeddingId = nextId()
            embeddings[embeddingId] = SpeakerEmbedding(
                id = embeddingId,
                memoryId = memoryId,
                speakerId = draft.speakerId,
                vector = draft.centroid,
                modelVersion = modelVersion,
                sourceOccurrenceId = occurrenceId,
                createdAtEpochMillis = now,
            )
            occurrences[occurrenceId] = SpeakerOccurrence(
                id = occurrenceId,
                memoryId = memoryId,
                ordinal = draft.ordinal,
                speakerId = draft.speakerId,
                link = draft.link,
                rejectedSpeakerIds = draft.rejectedSpeakerIds,
                totalSpeechMs = draft.totalSpeechMs,
                sampleStartMs = draft.sampleStartMs,
                sampleEndMs = draft.sampleEndMs,
                embeddingId = embeddingId,
            )
            draft.speakerId?.let { speakerId ->
                speakers[speakerId] = speakers.getValue(speakerId).copy(lastUsedAtEpochMillis = now)
            }
        }
        for (speakerId in speakers.keys.toList()) keepNewest(speakerId)
        published()
        Unit.right()
    }

    override suspend fun deleteForMemory(memoryId: String): Either<SpeakerError, Unit> =
        mutex.withLock<Either<SpeakerError, Unit>> {
            if (failDeletes) {
                return@withLock SpeakerError.Persistence(IllegalStateException("speaker store unavailable")).left()
            }
            for (speakerId in removeRows(memoryId)) keepNewest(speakerId)
            published()
            Unit.right()
        }

    override suspend fun clearAll(): Either<SpeakerError, Unit> = mutex.withLock<Either<SpeakerError, Unit>> {
        featureEnabled = false
        speakers.clear()
        embeddings.clear()
        occurrences.clear()
        published()
        Unit.right()
    }

    override suspend fun deleteRowsWithoutMemory(liveMemoryIds: Set<String>): Either<SpeakerError, Int> =
        mutex.withLock {
            val orphanEmbeddings = embeddings.values.filter { it.memoryId !in liveMemoryIds }.map { it.id }
            val orphanOccurrences = occurrences.values.filter { it.memoryId !in liveMemoryIds }.map { it.id }
            orphanEmbeddings.forEach { embeddings.remove(it) }
            orphanOccurrences.forEach { occurrences.remove(it) }
            published()
            (orphanEmbeddings.size + orphanOccurrences.size).right()
        }

    /** Removes the rows of [memoryId] and returns the speakers that lost an embedding. */
    private fun removeRows(memoryId: String): Set<String> {
        val affected = mutableSetOf<String>()
        for (embedding in embeddings.values.filter { it.memoryId == memoryId }) {
            embedding.speakerId?.let { affected += it }
            embeddings.remove(embedding.id)
        }
        for (occurrence in occurrences.values.filter { it.memoryId == memoryId }) {
            occurrences.remove(occurrence.id)
        }
        return affected
    }

    /** Drops a speaker's oldest embeddings beyond the limit and records the count. */
    private fun keepNewest(speakerId: String) {
        val speaker = speakers[speakerId] ?: return
        val owned = embeddings.values
            .filter { it.speakerId == speakerId }
            .sortedWith(compareByDescending<SpeakerEmbedding> { it.createdAtEpochMillis }.thenBy { it.id })
        for (dropped in owned.drop(MAX_EMBEDDINGS_PER_SPEAKER)) embeddings.remove(dropped.id)
        speakers[speakerId] = speaker.copy(embeddingCount = minOf(owned.size, MAX_EMBEDDINGS_PER_SPEAKER))
    }

    private fun nextId(): String = "speaker-test-${++generated}"

    private fun published() {
        events.tryEmit(Unit)
    }
}
