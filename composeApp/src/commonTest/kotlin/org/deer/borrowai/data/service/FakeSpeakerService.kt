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
import org.deer.borrowai.data.domain.MAX_SPEAKER_NAME_LENGTH
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerOccurrenceLink
import org.deer.borrowai.data.domain.SpeakerSummary
import org.deer.borrowai.data.domain.newSpeakerColorIndex
import org.deer.borrowai.data.service.SPEAKER_CONSENT_TEXT_VERSION

/** In-memory [SpeakerService] that follows the same rules as the Kotbase store, for tests that need no database. */
class FakeSpeakerService(
    enabled: Boolean = false,
    private val epochClock: EpochClock = EpochClock { 1_800_000_000_000L },
    /** When true, [replaceOccurrences] fails, so a test can check that a failed analysis keeps no rows. */
    var failWrites: Boolean = false,
) : SpeakerService {
    private val mutex = Mutex()
    private var featureEnabled = enabled
    private var consentTextVersion = if (enabled) SPEAKER_CONSENT_TEXT_VERSION else 0
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
        (featureEnabled && consentTextVersion == SPEAKER_CONSENT_TEXT_VERSION).right()
    }

    override suspend fun hasCurrentConsent(): Either<SpeakerError, Boolean> = mutex.withLock {
        (consentTextVersion == SPEAKER_CONSENT_TEXT_VERSION).right()
    }

    override suspend fun acceptConsent(): Either<SpeakerError, Unit> =
        mutex.withLock<Either<SpeakerError, Unit>> {
            consentTextVersion = SPEAKER_CONSENT_TEXT_VERSION
            featureEnabled = true
            published()
            Unit.right()
        }

    override suspend fun setFeatureEnabled(enabled: Boolean): Either<SpeakerError, Unit> =
        mutex.withLock<Either<SpeakerError, Unit>> {
            if (enabled && consentTextVersion != SPEAKER_CONSENT_TEXT_VERSION) {
                return@withLock SpeakerError.ConsentRequired.left()
            }
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
                trimmed.length > MAX_SPEAKER_NAME_LENGTH ->
                    SpeakerError.InvalidInput("A speaker name can be at most $MAX_SPEAKER_NAME_LENGTH characters.").left()
                speakers.values.any { it.name.equals(trimmed, ignoreCase = true) } ->
                    SpeakerError.NameTaken(trimmed).left()
                else -> {
                    val now = epochClock.nowEpochMillis()
                    val speaker = Speaker(nextId(), trimmed, now, now, 0, colorIndex = newSpeakerColorIndex(speakers.values))
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
                trimmed.length > MAX_SPEAKER_NAME_LENGTH ->
                    SpeakerError.InvalidInput("A speaker name can be at most $MAX_SPEAKER_NAME_LENGTH characters.").left()
                speakers.values.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) } ->
                    SpeakerError.NameTaken(trimmed).left()
                else -> {
                    speakers[id] = existing.copy(name = trimmed)
                    published()
                    Unit.right()
                }
            }
        }

    override suspend fun findSpeakerSummaries(
        memoryCreatedAtEpochMillis: Map<String, Long>,
    ): Either<SpeakerError, Map<String, SpeakerSummary>> =
        mutex.withLock {
            speakers.values.associate { speaker ->
                val linked = occurrences.values.filter {
                    it.speakerId == speaker.id && it.memoryId in memoryCreatedAtEpochMillis
                }
                speaker.id to SpeakerSummary(
                    speakerId = speaker.id,
                    linkedMemoryCount = linked.map { it.memoryId }.distinct().size,
                    lastHeardAtEpochMillis = linked.mapNotNull {
                        memoryCreatedAtEpochMillis[it.memoryId]
                    }.maxOrNull(),
                )
            }.right()
        }

    override suspend fun findOccurrencesForSpeaker(
        speakerId: String,
    ): Either<SpeakerError, List<SpeakerOccurrence>> = mutex.withLock {
        occurrences.values.filter { it.speakerId == speakerId }.right()
    }

    override suspend fun assignOccurrence(
        occurrenceId: String,
        speakerId: String?,
    ): Either<SpeakerError, Unit> = mutex.withLock<Either<SpeakerError, Unit>> {
        if (failWrites) return@withLock SpeakerError.Persistence(IllegalStateException("speaker store unavailable")).left()
        val occurrence = occurrences[occurrenceId] ?: return@withLock SpeakerError.NotFound(occurrenceId).left()
        if (speakerId != null && speakerId !in speakers) return@withLock SpeakerError.NotFound(speakerId).left()
        val embedding = embeddings[occurrence.embeddingId]
            ?: return@withLock SpeakerError.NotFound(occurrence.embeddingId).left()
        val previousSpeakerId = occurrence.speakerId
        val now = epochClock.nowEpochMillis()
        occurrences[occurrenceId] = occurrence.copy(
            speakerId = speakerId,
            link = if (speakerId == null) SpeakerLink.NONE else SpeakerLink.MANUAL,
            rejectedSpeakerIds = when {
                speakerId != null -> occurrence.rejectedSpeakerIds.filterNot { it == speakerId }
                previousSpeakerId != null -> (occurrence.rejectedSpeakerIds + previousSpeakerId).distinct()
                else -> occurrence.rejectedSpeakerIds
            },
        )
        embeddings[embedding.id] = embedding.copy(speakerId = speakerId)
        setOfNotNull(previousSpeakerId, speakerId).forEach { id ->
            speakers[id]?.let { speakers[id] = it.copy(lastUsedAtEpochMillis = if (id == speakerId) now else it.lastUsedAtEpochMillis) }
            keepNewest(id)
        }
        published()
        Unit.right()
    }

    override suspend fun rejectAutomaticMatch(occurrenceId: String): Either<SpeakerError, Unit> =
        mutex.withLock<Either<SpeakerError, Unit>> {
            if (failWrites) {
                return@withLock SpeakerError.Persistence(IllegalStateException("speaker store unavailable")).left()
            }
            val occurrence = occurrences[occurrenceId] ?: return@withLock SpeakerError.NotFound(occurrenceId).left()
            val speakerId = occurrence.speakerId
            if (occurrence.link != SpeakerLink.AUTO || speakerId == null) {
                return@withLock SpeakerError.InvalidInput("Only an automatic speaker match can be rejected.").left()
            }
            val embedding = embeddings[occurrence.embeddingId]
                ?: return@withLock SpeakerError.NotFound(occurrence.embeddingId).left()
            occurrences[occurrenceId] = occurrence.copy(
                speakerId = null,
                link = SpeakerLink.NONE,
                rejectedSpeakerIds = (occurrence.rejectedSpeakerIds + speakerId).distinct(),
            )
            embeddings[embedding.id] = embedding.copy(speakerId = null)
            keepNewest(speakerId)
            published()
            Unit.right()
        }

    override suspend fun restoreOccurrenceLink(
        expected: SpeakerOccurrenceLink,
        previous: SpeakerOccurrenceLink,
    ): Either<SpeakerError, Unit> = mutex.withLock<Either<SpeakerError, Unit>> {
        if (failWrites) return@withLock SpeakerError.Persistence(IllegalStateException("speaker store unavailable")).left()
        if (expected.occurrenceId != previous.occurrenceId) {
            return@withLock SpeakerError.InvalidInput("The undo snapshot refers to a different occurrence.").left()
        }
        val occurrence = occurrences[expected.occurrenceId]
            ?: return@withLock SpeakerError.NotFound(expected.occurrenceId).left()
        val current = SpeakerOccurrenceLink(
            occurrenceId = occurrence.id,
            speakerId = occurrence.speakerId,
            link = occurrence.link,
            rejectedSpeakerIds = occurrence.rejectedSpeakerIds,
        )
        if (current != expected) {
            return@withLock SpeakerError.InvalidInput("This speaker occurrence has changed, so Undo is no longer available.").left()
        }
        if (previous.speakerId != null && previous.speakerId !in speakers) {
            return@withLock SpeakerError.NotFound(previous.speakerId).left()
        }
        val embedding = embeddings[occurrence.embeddingId]
        occurrences[occurrence.id] = occurrence.copy(
            speakerId = previous.speakerId,
            link = previous.link,
            rejectedSpeakerIds = previous.rejectedSpeakerIds,
        )
        embedding?.let { embeddings[it.id] = it.copy(speakerId = previous.speakerId) }
        val now = epochClock.nowEpochMillis()
        previous.speakerId?.let { id ->
            speakers[id]?.let { speakers[id] = it.copy(lastUsedAtEpochMillis = now) }
        }
        setOfNotNull(occurrence.speakerId, previous.speakerId).forEach(::keepNewest)
        published()
        Unit.right()
    }

    override suspend fun mergeSpeakers(
        sourceId: String,
        targetId: String,
    ): Either<SpeakerError, Unit> = mutex.withLock<Either<SpeakerError, Unit>> {
        if (failWrites) return@withLock SpeakerError.Persistence(IllegalStateException("speaker store unavailable")).left()
        if (sourceId == targetId) {
            return@withLock SpeakerError.InvalidInput("Choose a different speaker to merge into.").left()
        }
        val source = speakers[sourceId] ?: return@withLock SpeakerError.NotFound(sourceId).left()
        val target = speakers[targetId] ?: return@withLock SpeakerError.NotFound(targetId).left()
        occurrences.values.toList().forEach { occurrence ->
            if (occurrence.speakerId == sourceId || sourceId in occurrence.rejectedSpeakerIds) {
                val nextSpeakerId = if (occurrence.speakerId == sourceId) targetId else occurrence.speakerId
                val rejected = occurrence.rejectedSpeakerIds.map { if (it == sourceId) targetId else it }
                    .distinct().filterNot { it == nextSpeakerId }
                occurrences[occurrence.id] = occurrence.copy(speakerId = nextSpeakerId, rejectedSpeakerIds = rejected)
            }
        }
        embeddings.values.filter { it.speakerId == sourceId }.forEach { embedding ->
            embeddings[embedding.id] = embedding.copy(speakerId = targetId)
        }
        speakers[targetId] = target.copy(
            lastUsedAtEpochMillis = maxOf(source.lastUsedAtEpochMillis, target.lastUsedAtEpochMillis),
        )
        speakers.remove(sourceId)
        keepNewest(targetId)
        published()
        Unit.right()
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

    override suspend fun findAllOccurrences(): Either<SpeakerError, List<SpeakerOccurrence>> =
        mutex.withLock { occurrences.values.toList().right() }

    override suspend fun replaceOccurrences(
        memoryId: String,
        modelVersion: Int,
        drafts: List<OccurrenceDraft>,
    ): Either<SpeakerError, Unit> = mutex.withLock<Either<SpeakerError, Unit>> {
        if (!featureEnabled || consentTextVersion != SPEAKER_CONSENT_TEXT_VERSION) {
            return@withLock SpeakerError.Disabled.left()
        }
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
        consentTextVersion = 0
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
