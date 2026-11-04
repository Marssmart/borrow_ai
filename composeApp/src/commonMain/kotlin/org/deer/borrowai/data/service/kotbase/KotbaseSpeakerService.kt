package org.deer.borrowai.data.service.kotbase

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import kotbase.Blob
import kotbase.Collection
import kotbase.DataSource
import kotbase.Database
import kotbase.Expression
import kotbase.Meta
import kotbase.MutableDocument
import kotbase.QueryBuilder
import kotbase.Result
import kotbase.SelectResult
import kotbase.ValueIndexConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import okio.Buffer
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MAX_EMBEDDINGS_PER_SPEAKER
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.newSpeakerColorIndex
import org.deer.borrowai.data.service.SpeakerError
import org.deer.borrowai.data.service.SpeakerService

/**
 * [SpeakerService] backed by Couchbase Lite through Kotbase. Speaker data lives in its own collection of the borrow-ai
 * database, apart from memories (decision Q-9). Each document has a `type` field, as memory documents do, so the one
 * collection holds speakers, occurrences, embeddings, and the feature setting.
 *
 * A write reads what it needs first, then applies only saves and deletes. The analysis of one memory is applied inside
 * one [Database.inBatch], so a retry replaces a partial result instead of adding to it.
 */
class KotbaseSpeakerService private constructor(
    private val database: Database,
    private val collection: Collection,
    private val epochClock: EpochClock,
    private val idGenerator: () -> String,
) : SpeakerService {
    private val mutex = Mutex()
    private val changeEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = changeEvents.asSharedFlow()

    override suspend fun isFeatureEnabled(): Either<SpeakerError, Boolean> =
        withContext(Dispatchers.Default) { persisting { readFeatureEnabled() } }

    override suspend fun setFeatureEnabled(enabled: Boolean): Either<SpeakerError, Unit> = mutex.withLock {
        write { saveSettings(enabled) }
    }

    override suspend fun findSpeakers(): Either<SpeakerError, List<Speaker>> =
        withContext(Dispatchers.Default) { persisting { readSpeakers() } }

    override suspend fun findSpeaker(id: String): Either<SpeakerError, Speaker> = either {
        val speaker = withContext(Dispatchers.Default) {
            persisting { readSpeakers(Meta.id.equalTo(Expression.string(id))).firstOrNull() }
        }.bind()
        speaker ?: raise(SpeakerError.NotFound(id))
    }

    override suspend fun createSpeaker(name: String): Either<SpeakerError, Speaker> = mutex.withLock {
        either {
            val trimmed = checkName(name, exceptId = null).bind()
            val existing = withContext(Dispatchers.Default) { persisting { readSpeakers() } }.bind()
            val now = epochClock.nowEpochMillis()
            val speaker = Speaker(
                id = idGenerator(),
                name = trimmed,
                createdAtEpochMillis = now,
                lastUsedAtEpochMillis = now,
                embeddingCount = 0,
                colorIndex = newSpeakerColorIndex(existing),
            )
            write { saveSpeaker(speaker) }.bind()
            speaker
        }
    }

    override suspend fun renameSpeaker(id: String, name: String): Either<SpeakerError, Unit> = mutex.withLock {
        either {
            val existing = findSpeaker(id).bind()
            val trimmed = checkName(name, exceptId = id).bind()
            write { saveSpeaker(existing.copy(name = trimmed)) }.bind()
        }
    }

    override suspend fun deleteSpeaker(id: String): Either<SpeakerError, Unit> = mutex.withLock {
        either {
            findSpeaker(id).bind()
            write { unlinkAndDeleteSpeaker(id) }.bind()
        }
    }

    override suspend fun findSpeakerEmbeddings(modelVersion: Int): Either<SpeakerError, List<SpeakerEmbedding>> =
        withContext(Dispatchers.Default) {
            // Filtered before decoding, so an unattached or other-model row is never decoded.
            persisting {
                readEmbeddings { row ->
                    row.getString(KEY_SPEAKER_ID) != null && row.getInt(KEY_MODEL_VERSION) == modelVersion
                }
            }
        }

    override suspend fun findOccurrences(memoryId: String): Either<SpeakerError, List<SpeakerOccurrence>> =
        withContext(Dispatchers.Default) {
            persisting { readOccurrences { row -> row.getString(KEY_MEMORY_ID) == memoryId }.sortedBy { it.ordinal } }
        }

    override suspend fun findAllOccurrences(): Either<SpeakerError, List<SpeakerOccurrence>> =
        withContext(Dispatchers.Default) { persisting { readOccurrences() } }

    override suspend fun replaceOccurrences(
        memoryId: String,
        modelVersion: Int,
        drafts: List<OccurrenceDraft>,
    ): Either<SpeakerError, Unit> = mutex.withLock {
        either {
            ensureEnabled().bind()
            val now = epochClock.nowEpochMillis()
            val rows = buildRows(memoryId, modelVersion, drafts, now).bind()
            val linkedSpeakerIds = rows.mapNotNullTo(mutableSetOf()) { it.occurrence.speakerId }
            ensureSpeakersExist(linkedSpeakerIds).bind()
            val plan = withContext(Dispatchers.Default) {
                persisting { planMemory(memoryId, rows, linkedSpeakerIds, usedAtEpochMillis = now) }
            }.bind()
            // Exceptions are caught inside the batch and returned, so none crosses Couchbase's native batch boundary.
            // A partial write is harmless: the next analysis rebuilds these rows from scratch.
            var batchFailure: Exception? = null
            write {
                database.inBatch {
                    try {
                        applyPlan(plan)
                    } catch (exception: Exception) {
                        batchFailure = exception
                    }
                    Unit
                }
            }.bind()
            batchFailure?.let { failure ->
                // The batch can commit rows written before the failure. Remove them so a failed attempt leaves no partial result.
                write {
                    applyPlan(planMemory(memoryId, rows = emptyList(), linkedSpeakerIds = emptySet(), usedAtEpochMillis = null))
                }.bind()
                raise(SpeakerError.Persistence(failure))
            }
        }
    }

    override suspend fun deleteForMemory(memoryId: String): Either<SpeakerError, Unit> = mutex.withLock {
        // Not batched: each step is idempotent, and the purger only drops the memory record after this succeeds.
        write {
            applyPlan(planMemory(memoryId, rows = emptyList(), linkedSpeakerIds = emptySet(), usedAtEpochMillis = null))
        }
    }

    override suspend fun clearAll(): Either<SpeakerError, Unit> = mutex.withLock {
        either {
            // Analysis stops first, so a partly finished withdrawal cannot leave analysis running.
            write { saveSettings(enabled = false) }.bind()
            write { deleteEverythingExceptSettings() }.bind()
        }
    }

    override suspend fun deleteRowsWithoutMemory(liveMemoryIds: Set<String>): Either<SpeakerError, Int> = mutex.withLock {
        either {
            val orphanMemoryIds = withContext(Dispatchers.Default) {
                persisting {
                    (rowRefs(TYPE_EMBEDDING) + rowRefs(TYPE_OCCURRENCE))
                        .map { it.memoryId }
                        .filterTo(mutableSetOf()) { it !in liveMemoryIds }
                }
            }.bind()
            // Each orphaned memory goes through the same plan as a deletion, so speaker counts are corrected too.
            val plans = withContext(Dispatchers.Default) {
                persisting {
                    orphanMemoryIds.map { memoryId ->
                        planMemory(memoryId, rows = emptyList(), linkedSpeakerIds = emptySet(), usedAtEpochMillis = null)
                    }
                }
            }.bind()
            write {
                plans.forEach { applyPlan(it) }
                plans.sumOf { it.deleteIds.size }
            }.bind()
        }
    }

    /** Trims [name] and checks that it is not blank and no other speaker already has it, ignoring case. */
    private suspend fun checkName(name: String, exceptId: String?): Either<SpeakerError, String> = either {
        val trimmed = name.trim()
        ensure(trimmed.isNotEmpty()) { SpeakerError.InvalidInput("A speaker name cannot be blank.") }
        val taken = withContext(Dispatchers.Default) { persisting { readSpeakers() } }.bind()
            .any { it.id != exceptId && it.name.equals(trimmed, ignoreCase = true) }
        ensure(!taken) { SpeakerError.NameTaken(trimmed) }
        trimmed
    }

    private suspend fun ensureSpeakersExist(speakerIds: Set<String>): Either<SpeakerError, Unit> = either {
        val missing = withContext(Dispatchers.Default) {
            persisting { speakerIds.firstOrNull { !isSpeaker(it) } }
        }.bind()
        ensure(missing == null) { SpeakerError.NotFound(missing.orEmpty()) }
    }

    /**
     * Builds the rows for [drafts] before any write, so an invalid draft fails without touching the database. Ids are
     * assigned here, so the batch itself only stores what it is given.
     */
    private fun buildRows(
        memoryId: String,
        modelVersion: Int,
        drafts: List<OccurrenceDraft>,
        now: Long,
    ): Either<SpeakerError, List<OccurrenceRow>> = try {
        drafts.map { draft ->
            val occurrenceId = idGenerator()
            val embeddingId = idGenerator()
            OccurrenceRow(
                occurrence = SpeakerOccurrence(
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
                ),
                embedding = SpeakerEmbedding(
                    id = embeddingId,
                    memoryId = memoryId,
                    speakerId = draft.speakerId,
                    vector = draft.centroid,
                    modelVersion = modelVersion,
                    sourceOccurrenceId = occurrenceId,
                    createdAtEpochMillis = now,
                ),
            )
        }.right()
    } catch (exception: IllegalArgumentException) {
        SpeakerError.InvalidInput(exception.message.orEmpty()).left()
    }

    /**
     * Writes [plan]. New rows go in first and old rows come out last. Until the old rows are gone, a retry still finds
     * them, so it recomputes the same speaker counts and cap, and its deletes cover the new rows as well.
     */
    private fun applyPlan(plan: MemoryPlan) {
        for (row in plan.rows) {
            saveEmbedding(row.embedding)
            saveOccurrence(row.occurrence)
        }
        for (change in plan.changes) applySpeakerChange(change)
        for (id in plan.deleteIds) deleteDocument(id)
    }

    private fun applySpeakerChange(change: SpeakerChange) {
        for (id in change.dropEmbeddingIds) deleteDocument(id)
        updateDocument(change.speakerId) {
            setInt(KEY_EMBEDDING_COUNT, change.embeddingCount)
            change.usedAtEpochMillis?.let { setLong(KEY_LAST_USED_AT, it) }
        }
    }

    /** Applies [change] to the stored document [id] and saves it. A missing document is left alone. */
    private fun updateDocument(id: String, change: MutableDocument.() -> Unit) {
        val document = collection.getDocument(id)?.toMutable() ?: return
        document.change()
        collection.save(document)
    }

    /**
     * Reads everything a change to [memoryId] needs before anything is written. The memory's old rows are found by its
     * memoryId. For each speaker it touches, the other embeddings are read as references only, so a damaged vector cannot
     * block a purge. The cap keeps the new rows and, of the rest, the newest embeddings.
     */
    private fun planMemory(
        memoryId: String,
        rows: List<OccurrenceRow>,
        linkedSpeakerIds: Set<String>,
        usedAtEpochMillis: Long?,
    ): MemoryPlan {
        val oldRows = rowRefs(TYPE_EMBEDDING, memoryIdIs(memoryId)) + rowRefs(TYPE_OCCURRENCE, memoryIdIs(memoryId))
        val incoming = rows.mapNotNull { it.embedding.speakerId }.groupingBy { it }.eachCount()
        val touched = oldRows.mapNotNullTo(mutableSetOf()) { it.speakerId } + linkedSpeakerIds
        val changes = touched.map { speakerId ->
            val arriving = incoming[speakerId] ?: 0
            val others = rowRefs(TYPE_EMBEDDING, speakerIdIs(speakerId))
                .filter { it.memoryId != memoryId }
                .sortedWith(compareByDescending<RowRef> { it.createdAtEpochMillis }.thenBy { it.id })
            val room = (MAX_EMBEDDINGS_PER_SPEAKER - arriving).coerceAtLeast(0)
            SpeakerChange(
                speakerId = speakerId,
                dropEmbeddingIds = others.drop(room).map { it.id },
                embeddingCount = arriving + minOf(others.size, room),
                usedAtEpochMillis = usedAtEpochMillis?.takeIf { speakerId in linkedSpeakerIds },
            )
        }
        return MemoryPlan(deleteIds = oldRows.map { it.id }, rows = rows, changes = changes)
    }

    /**
     * Removes a speaker. Its voiceprints go with it, as the privacy rules require. Its occurrences become unknown, and
     * memories are not touched.
     */
    private fun unlinkAndDeleteSpeaker(speakerId: String) {
        for (ref in rowRefs(TYPE_EMBEDDING, speakerIdIs(speakerId))) deleteDocument(ref.id)
        for (ref in rowRefs(TYPE_OCCURRENCE, speakerIdIs(speakerId))) {
            updateDocument(ref.id) {
                setString(KEY_SPEAKER_ID, null)
                setString(KEY_LINK, SpeakerLink.NONE.name)
            }
        }
        deleteDocument(speakerId)
    }

    private fun memoryIdIs(memoryId: String): Expression =
        Expression.property(KEY_MEMORY_ID).equalTo(Expression.string(memoryId))

    private fun speakerIdIs(speakerId: String): Expression =
        Expression.property(KEY_SPEAKER_ID).equalTo(Expression.string(speakerId))

    private fun isSpeaker(id: String): Boolean = collection.getDocument(id)?.getString(KEY_TYPE) == TYPE_SPEAKER

    /** Fails with [SpeakerError.Disabled] when the feature is off. Callers hold [mutex], so a withdrawal cannot race it. */
    private suspend fun ensureEnabled(): Either<SpeakerError, Unit> = either {
        val enabled = withContext(Dispatchers.Default) { persisting { readFeatureEnabled() } }.bind()
        ensure(enabled) { SpeakerError.Disabled }
    }

    private fun readFeatureEnabled(): Boolean = collection.getDocument(SETTINGS_ID)
        ?.takeIf { it.getString(KEY_TYPE) == TYPE_SETTINGS }
        ?.getBoolean(KEY_ENABLED)
        ?: false

    private fun deleteEverythingExceptSettings() {
        val ids = QueryBuilder
            .select(idColumn())
            .from(DataSource.collection(collection))
            .execute()
            .allResults()
            .mapNotNull { it.getString(COLUMN_ID) }
        for (id in ids) {
            if (id != SETTINGS_ID) deleteDocument(id)
        }
    }

    private fun saveSettings(enabled: Boolean) {
        val document = collection.getDocument(SETTINGS_ID)?.toMutable() ?: MutableDocument(SETTINGS_ID)
        document.apply {
            setString(KEY_TYPE, TYPE_SETTINGS)
            setBoolean(KEY_ENABLED, enabled)
        }
        collection.save(document)
    }

    private fun saveSpeaker(speaker: Speaker) {
        val document = collection.getDocument(speaker.id)?.toMutable() ?: MutableDocument(speaker.id)
        document.apply {
            setString(KEY_TYPE, TYPE_SPEAKER)
            setString(KEY_NAME, speaker.name)
            setLong(KEY_CREATED_AT, speaker.createdAtEpochMillis)
            setLong(KEY_LAST_USED_AT, speaker.lastUsedAtEpochMillis)
            setInt(KEY_EMBEDDING_COUNT, speaker.embeddingCount)
            setInt(KEY_COLOR_INDEX, speaker.colorIndex)
        }
        collection.save(document)
    }

    private fun saveOccurrence(occurrence: SpeakerOccurrence) {
        val document = collection.getDocument(occurrence.id)?.toMutable() ?: MutableDocument(occurrence.id)
        document.apply {
            setString(KEY_TYPE, TYPE_OCCURRENCE)
            setString(KEY_MEMORY_ID, occurrence.memoryId)
            setInt(KEY_ORDINAL, occurrence.ordinal)
            setString(KEY_SPEAKER_ID, occurrence.speakerId)
            setString(KEY_LINK, occurrence.link.name)
            setString(KEY_REJECTED_SPEAKER_IDS, occurrence.rejectedSpeakerIds.joinToString(ID_SEPARATOR))
            setLong(KEY_TOTAL_SPEECH_MS, occurrence.totalSpeechMs)
            setLong(KEY_SAMPLE_START_MS, occurrence.sampleStartMs)
            setLong(KEY_SAMPLE_END_MS, occurrence.sampleEndMs)
            setString(KEY_EMBEDDING_ID, occurrence.embeddingId)
        }
        collection.save(document)
    }

    private fun saveEmbedding(embedding: SpeakerEmbedding) {
        val document = collection.getDocument(embedding.id)?.toMutable() ?: MutableDocument(embedding.id)
        document.apply {
            setString(KEY_TYPE, TYPE_EMBEDDING)
            setString(KEY_MEMORY_ID, embedding.memoryId)
            setString(KEY_SPEAKER_ID, embedding.speakerId)
            setBlob(KEY_VECTOR, Blob(VECTOR_CONTENT_TYPE, embedding.vector.toBytes()))
            setInt(KEY_MODEL_VERSION, embedding.modelVersion)
            setString(KEY_SOURCE_OCCURRENCE_ID, embedding.sourceOccurrenceId)
            setLong(KEY_CREATED_AT, embedding.createdAtEpochMillis)
        }
        collection.save(document)
    }

    private fun deleteDocument(id: String) {
        collection.getDocument(id)?.let { collection.delete(it) }
    }

    /** Runs [block] outside the caller's cancellation, then announces the change when it committed. */
    private suspend fun <T> write(block: () -> T): Either<SpeakerError, T> = withContext(NonCancellable) {
        withContext(Dispatchers.Default) { persisting(block) }.also { if (it.isRight()) changeEvents.tryEmit(Unit) }
    }

    /** Every field [toSpeaker] reads must be selected here. An unselected int reads as 0 without an error. */
    private fun readSpeakers(condition: Expression? = null): List<Speaker> = queryRows(
        TYPE_SPEAKER,
        condition,
        idColumn(),
        SelectResult.property(KEY_NAME),
        SelectResult.property(KEY_CREATED_AT),
        SelectResult.property(KEY_LAST_USED_AT),
        SelectResult.property(KEY_EMBEDDING_COUNT),
        SelectResult.property(KEY_COLOR_INDEX),
    ).map { it.toSpeaker() }.sortedByDescending { it.lastUsedAtEpochMillis }

    private fun readEmbeddings(keep: (Result) -> Boolean = { true }): List<SpeakerEmbedding> = queryRows(
        TYPE_EMBEDDING,
        null,
        idColumn(),
        SelectResult.property(KEY_MEMORY_ID),
        SelectResult.property(KEY_SPEAKER_ID),
        SelectResult.property(KEY_VECTOR),
        SelectResult.property(KEY_MODEL_VERSION),
        SelectResult.property(KEY_SOURCE_OCCURRENCE_ID),
        SelectResult.property(KEY_CREATED_AT),
    ).filter(keep).map { it.toEmbedding() }

    private fun readOccurrences(keep: (Result) -> Boolean = { true }): List<SpeakerOccurrence> = queryRows(
        TYPE_OCCURRENCE,
        null,
        idColumn(),
        SelectResult.property(KEY_MEMORY_ID),
        SelectResult.property(KEY_ORDINAL),
        SelectResult.property(KEY_SPEAKER_ID),
        SelectResult.property(KEY_LINK),
        SelectResult.property(KEY_REJECTED_SPEAKER_IDS),
        SelectResult.property(KEY_TOTAL_SPEECH_MS),
        SelectResult.property(KEY_SAMPLE_START_MS),
        SelectResult.property(KEY_SAMPLE_END_MS),
        SelectResult.property(KEY_EMBEDDING_ID),
    ).filter(keep).map { it.toOccurrence() }

    /** Runs a query over the documents of [type], narrowed by [condition] when one is given. */
    private fun queryRows(type: String, condition: Expression?, vararg columns: SelectResult): List<Result> {
        val ofType = Expression.property(KEY_TYPE).equalTo(Expression.string(type))
        return QueryBuilder
            .select(*columns)
            .from(DataSource.collection(collection))
            .where(if (condition == null) ofType else ofType.and(condition))
            .execute()
            .allResults()
    }

    /** Reads the identity and links of the rows that match [condition], or of every row of [type]. */
    private fun rowRefs(type: String, condition: Expression? = null): List<RowRef> = queryRows(
        type,
        condition,
        idColumn(),
        SelectResult.property(KEY_MEMORY_ID),
        SelectResult.property(KEY_SPEAKER_ID),
        SelectResult.property(KEY_CREATED_AT),
    ).map { row ->
        RowRef(
            id = row.getString(COLUMN_ID).orEmpty(),
            memoryId = row.getString(KEY_MEMORY_ID).orEmpty(),
            speakerId = row.getString(KEY_SPEAKER_ID),
            createdAtEpochMillis = row.getLong(KEY_CREATED_AT),
        )
    }

    private fun idColumn(): SelectResult = SelectResult.expression(Meta.id).`as`(COLUMN_ID)

    private fun Result.toSpeaker(): Speaker {
        val id = getString(COLUMN_ID).orEmpty()
        return decoding(id) {
            Speaker(
                id = id,
                name = getString(KEY_NAME).orEmpty(),
                createdAtEpochMillis = getLong(KEY_CREATED_AT),
                lastUsedAtEpochMillis = getLong(KEY_LAST_USED_AT),
                embeddingCount = getInt(KEY_EMBEDDING_COUNT),
                colorIndex = getInt(KEY_COLOR_INDEX),
            )
        }
    }

    private fun Result.toEmbedding(): SpeakerEmbedding {
        val id = getString(COLUMN_ID).orEmpty()
        return decoding(id) {
            SpeakerEmbedding(
                id = id,
                memoryId = getString(KEY_MEMORY_ID).orEmpty(),
                speakerId = getString(KEY_SPEAKER_ID),
                vector = requireNotNull(getBlob(KEY_VECTOR)?.content).toFloatVector(),
                modelVersion = getInt(KEY_MODEL_VERSION),
                sourceOccurrenceId = getString(KEY_SOURCE_OCCURRENCE_ID).orEmpty(),
                createdAtEpochMillis = getLong(KEY_CREATED_AT),
            )
        }
    }

    private fun Result.toOccurrence(): SpeakerOccurrence {
        val id = getString(COLUMN_ID).orEmpty()
        return decoding(id) {
            SpeakerOccurrence(
                id = id,
                memoryId = getString(KEY_MEMORY_ID).orEmpty(),
                ordinal = getInt(KEY_ORDINAL),
                speakerId = getString(KEY_SPEAKER_ID),
                link = SpeakerLink.valueOf(requireNotNull(getString(KEY_LINK))),
                rejectedSpeakerIds = decodeIds(getString(KEY_REJECTED_SPEAKER_IDS)),
                totalSpeechMs = getLong(KEY_TOTAL_SPEECH_MS),
                sampleStartMs = getLong(KEY_SAMPLE_START_MS),
                sampleEndMs = getLong(KEY_SAMPLE_END_MS),
                embeddingId = getString(KEY_EMBEDDING_ID).orEmpty(),
            )
        }
    }

    /** A new occurrence and its centroid embedding, built before any write. */
    private class OccurrenceRow(val occurrence: SpeakerOccurrence, val embedding: SpeakerEmbedding)

    /** The identity and links of a stored row, read without its vector. */
    private class RowRef(val id: String, val memoryId: String, val speakerId: String?, val createdAtEpochMillis: Long)

    /** The change one speaker needs for a memory: voiceprints to drop, the new count, and the last-used time if linked. */
    private class SpeakerChange(
        val speakerId: String,
        val dropEmbeddingIds: List<String>,
        val embeddingCount: Int,
        val usedAtEpochMillis: Long?,
    )

    /** Everything one memory's write needs, read before any change is applied. */
    private class MemoryPlan(
        val deleteIds: List<String>,
        val rows: List<OccurrenceRow>,
        val changes: List<SpeakerChange>,
    )

    private fun decodeIds(raw: String?): List<String> =
        raw?.split(ID_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    companion object {
        private const val COLLECTION_NAME = "speakers"
        private const val INDEX_BY_MEMORY = "speakers-by-memory"
        private const val INDEX_BY_SPEAKER = "speakers-by-speaker"
        private const val SETTINGS_ID = "speaker-settings"
        private const val COLUMN_ID = "id"
        private const val KEY_TYPE = "type"
        private const val TYPE_SPEAKER = "speaker"
        private const val TYPE_EMBEDDING = "embedding"
        private const val TYPE_OCCURRENCE = "occurrence"
        private const val TYPE_SETTINGS = "settings"
        private const val KEY_NAME = "name"
        private const val KEY_CREATED_AT = "createdAtEpochMillis"
        private const val KEY_LAST_USED_AT = "lastUsedAtEpochMillis"
        private const val KEY_EMBEDDING_COUNT = "embeddingCount"
        private const val KEY_COLOR_INDEX = "colorIndex"
        private const val KEY_MEMORY_ID = "memoryId"
        private const val KEY_SPEAKER_ID = "speakerId"
        private const val KEY_VECTOR = "vector"
        private const val KEY_MODEL_VERSION = "modelVersion"
        private const val KEY_SOURCE_OCCURRENCE_ID = "sourceOccurrenceId"
        private const val KEY_ORDINAL = "ordinal"
        private const val KEY_LINK = "link"
        private const val KEY_REJECTED_SPEAKER_IDS = "rejectedSpeakerIds"
        private const val KEY_TOTAL_SPEECH_MS = "totalSpeechMs"
        private const val KEY_SAMPLE_START_MS = "sampleStartMs"
        private const val KEY_SAMPLE_END_MS = "sampleEndMs"
        private const val KEY_EMBEDDING_ID = "embeddingId"
        private const val KEY_ENABLED = "enabled"
        private const val VECTOR_CONTENT_TYPE = "application/octet-stream"
        private const val ID_SEPARATOR = "|"

        /**
         * Opens the speaker store on [database], creating its collection on first use. Speaker analysis stays off until
         * the user opts in.
         */
        @OptIn(ExperimentalUuidApi::class)
        suspend fun open(
            database: Database,
            epochClock: EpochClock,
            idGenerator: () -> String = { Uuid.random().toString() },
        ): Either<SpeakerError, KotbaseSpeakerService> = withContext(Dispatchers.Default) {
            persisting {
                database.createCollection(COLLECTION_NAME).also { collection ->
                    // An index only speeds up lookups, so a failure to create one does not stop the store from opening.
                    runCatching { collection.createIndex(INDEX_BY_MEMORY, ValueIndexConfiguration(KEY_MEMORY_ID)) }
                    runCatching { collection.createIndex(INDEX_BY_SPEAKER, ValueIndexConfiguration(KEY_SPEAKER_ID)) }
                }
            }.map { collection ->
                KotbaseSpeakerService(database, collection, epochClock, idGenerator)
            }
        }
    }
}

/** Runs [block], mapping failures to [SpeakerError.Persistence], or [SpeakerError.Malformed] for a bad document. */
private fun <T> persisting(block: () -> T): Either<SpeakerError, T> = try {
    block().right()
} catch (exception: CancellationException) {
    throw exception
} catch (exception: MalformedDocument) {
    SpeakerError.Malformed(exception.id).left()
} catch (exception: Exception) {
    SpeakerError.Persistence(exception).left()
}

/** Runs [block] to decode a stored document. A value that breaks a rule becomes [MalformedDocument]. */
private inline fun <T> decoding(id: String, block: () -> T): T = try {
    block()
} catch (exception: IllegalArgumentException) {
    throw MalformedDocument(id)
}

/** Raised while decoding a stored speaker document. Mapped to [SpeakerError.Malformed]. */
private class MalformedDocument(val id: String) : RuntimeException("Malformed speaker document.")

/** Little-endian 32-bit floats. [toFloatVector] reads the same layout back. */
private fun FloatArray.toBytes(): ByteArray {
    val buffer = Buffer()
    for (value in this) buffer.writeIntLe(value.toRawBits())
    return buffer.readByteArray()
}

private fun ByteArray.toFloatVector(): FloatArray {
    require(size % 4 == 0) { "A stored vector must hold whole floats." }
    val buffer = Buffer()
    buffer.write(this)
    return FloatArray(size / 4) { Float.fromBits(buffer.readIntLe()) }
}
