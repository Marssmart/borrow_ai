package org.deer.borrowai.data.service.kotbase

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotbase.DataSource
import kotbase.Database
import kotbase.Document
import kotbase.Expression
import kotbase.Meta
import kotbase.MutableDocument
import kotbase.Ordering
import kotbase.QueryBuilder
import kotbase.Result
import kotbase.SelectResult
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.deer.borrowai.data.domain.Category
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.parseCategoriesString
import org.deer.borrowai.data.domain.toCategoriesString
import org.deer.borrowai.data.domain.SummaryStatus
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.domain.afterFinishedAttempt
import org.deer.borrowai.data.domain.claimedForTranscription
import org.deer.borrowai.data.domain.isMissingTranscription
import org.deer.borrowai.data.domain.migrateLegacySummaryState
import org.deer.borrowai.data.domain.summaryStatusFor
import org.deer.borrowai.data.domain.withEditedSummary
import org.deer.borrowai.data.domain.requeuedForTranscription
import org.deer.borrowai.data.domain.withFailedTranscription
import org.deer.borrowai.data.domain.withFinishedTranscription
import org.deer.borrowai.data.domain.withGaveUp
import org.deer.borrowai.data.domain.withSummary
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.StatefulMemoryService

/**
 * [MemoryService] backed by a Couchbase Lite database through Kotbase.
 *
 * Each memory is stored as one document whose id is the memory id. Documents carry a `type`
 * discriminator so the database can hold other document kinds later.
 */
class KotbaseMemoryService private constructor(
    private val database: Database,
    private val epochClock: EpochClock,
    private val idGenerator: () -> String,
) : StatefulMemoryService() {
    private val mutex = Mutex()

    override suspend fun findById(id: String): Either<MemoryError, Memory> = withContext(Dispatchers.Default) {
        readMemory(id)
    }

    override suspend fun create(
        draft: MemoryDraft,
        id: String?,
        transcriptionStatus: TranscriptionStatus,
    ): Either<MemoryError, Memory> = mutex.withLock {
        val memory = Memory(
            id = id ?: idGenerator(),
            createdAtEpochMillis = epochClock.nowEpochMillis(),
            title = draft.title,
            summary = draft.summary,
            summaryStatus = summaryStatusFor(draft.summary),
            transcription = draft.transcription,
            recordingPath = draft.recordingPath,
            transcriptionStatus = transcriptionStatus,
            categories = draft.categories,
        )
        writeAndPublish { database.save(memory.toDocument()) }.map { memory }
    }

    override suspend fun claimNextPendingTranscription(): Either<MemoryError, Memory?> = mutex.withLock {
        either {
            val candidate = withContext(Dispatchers.Default) { readAll() }.bind()
                .filter { !it.deleted && it.transcriptionStatus == TranscriptionStatus.PENDING }
                .minByOrNull(Memory::createdAtEpochMillis)
            if (candidate == null) {
                null
            } else {
                val claimed = candidate.claimedForTranscription()
                writeAndPublish { database.save(claimed.toDocument()) }.bind()
                claimed
            }
        }
    }

    override suspend fun completeTranscription(id: String, transcription: String): Either<MemoryError, Unit> =
        mutex.withLock {
            either {
                val existing = withContext(Dispatchers.Default) { readActive(id) }.bind()
                writeAndPublish { database.save(existing.withFinishedTranscription(transcription).toDocument()) }.bind()
            }
        }

    override suspend fun failTranscription(id: String, error: String?): Either<MemoryError, Unit> = mutex.withLock {
        either {
            val existing = withContext(Dispatchers.Default) { readActive(id) }.bind()
            writeAndPublish { database.save(existing.withFailedTranscription(error).toDocument()) }.bind()
        }
    }

    override suspend fun requeueUnfinishedTranscriptions(): Either<MemoryError, Int> = mutex.withLock {
        either {
            val unfinished = withContext(Dispatchers.Default) { readAll() }.bind()
                .filter { !it.deleted && (it.transcriptionStatus == TranscriptionStatus.IN_PROGRESS || it.isMissingTranscription()) }
            unfinished.forEach { memory ->
                writeAndPublish { database.save(memory.requeuedForTranscription().toDocument()) }.bind()
            }
            unfinished.size
        }
    }

    override suspend fun update(id: String, draft: MemoryDraft): Either<MemoryError, Unit> = mutex.withLock {
        either {
            val existing = withContext(Dispatchers.Default) { readActive(id) }.bind()
            val updated = existing.withEditedSummary(draft.summary).copy(
                title = draft.title,
                transcription = draft.transcription,
                recordingPath = draft.recordingPath,
                categories = draft.categories,
            )
            writeAndPublish { database.save(updated.toDocument()) }.bind()
        }
    }

    override suspend fun updateCategories(id: String, categories: List<Int>): Either<MemoryError, Unit> =
        updateActive(id) { it.copy(categories = categories) }

    override suspend fun delete(id: String): Either<MemoryError, Unit> = mutex.withLock {
        either {
            val existing = withContext(Dispatchers.Default) { readActive(id) }.bind()
            writeAndPublish { database.save(existing.copy(deleted = true).toDocument()) }.bind()
        }
    }

    override suspend fun purge(id: String): Boolean = mutex.withLock {
        withContext(NonCancellable) {
            val purged = withContext(Dispatchers.Default) {
                val document = database.getDocument(id)
                    ?.takeIf { it.getString(KEY_TYPE) == TYPE_MEMORY && it.getBoolean(COLUMN_DELETED) }
                    ?: return@withContext false
                database.delete(document)
                true
            }
            if (purged) publishMemories()
            purged
        }
    }

    override suspend fun findAll(): Either<MemoryError, List<Memory>> = withContext(Dispatchers.Default) {
        readAll()
    }

    override suspend fun findCategories(): Either<MemoryError, List<Category>> = withContext(Dispatchers.Default) {
        readCategories()
    }

    override suspend fun createCategory(name: String, description: String): Either<MemoryError, Category> =
        mutex.withLock {
            either {
                val existing = withContext(Dispatchers.Default) { readCategories() }.bind()
                val category = try {
                    Category(
                        id = (existing.maxOfOrNull(Category::id) ?: 0) + 1,
                        name = name,
                        description = description,
                    )
                } catch (e: IllegalArgumentException) {
                    raise(MemoryError.InvalidInput(e.message.orEmpty()))
                }
                withContext(NonCancellable) {
                    withContext(Dispatchers.Default) { persisting { database.save(category.toDocument()) } }
                }.bind()
                category
            }
        }

    override suspend fun recordFinishedAttempt(id: String, error: String?): Either<MemoryError, Unit> =
        updateActive(id) { it.afterFinishedAttempt(error = error, empty = false) }

    override suspend fun recordEmpty(id: String): Either<MemoryError, Unit> =
        updateActive(id) { it.afterFinishedAttempt(error = null, empty = true) }

    override suspend fun recordSummary(id: String, summary: String): Either<MemoryError, Unit> =
        updateActive(id) { it.withSummary(summary) }

    override suspend fun giveUpSummary(id: String): Either<MemoryError, Unit> =
        updateActive(id) { it.withGaveUp() }

    private suspend fun updateActive(id: String, change: (Memory) -> Memory): Either<MemoryError, Unit> =
        mutex.withLock {
            either {
                val existing = withContext(Dispatchers.Default) { readActive(id) }.bind()
                writeAndPublish { database.save(change(existing).toDocument()) }.bind()
            }
        }

    /**
     * Runs [write] and refreshes the published state as one unit. Cancellation is deferred until
     * both finish, so a committed write never leaves [getState] stale. A refresh failure is
     * returned so the caller learns that [getState] may not reflect the write.
     */
    private suspend fun writeAndPublish(write: () -> Unit): Either<MemoryError, Unit> = withContext(NonCancellable) {
        val written = withContext(Dispatchers.Default) { persisting(write) }
        when (written) {
            is Either.Left -> written
            is Either.Right -> publishMemories()
        }
    }

    private suspend fun publishMemories(): Either<MemoryError, Unit> =
        withContext(Dispatchers.Default) { readAll() }
            .map { all -> memories.value = all.filterNot(Memory::deleted) }

    /** Runs [block], mapping any non-cancellation failure to [MemoryError.Persistence]. */
    private fun <T> persisting(block: () -> T): Either<MemoryError, T> = try {
        block().right()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MemoryError.Persistence(e).left()
    }

    /** Runs [block] to decode stored content, mapping validation failures to [MemoryError.Malformed]. */
    private inline fun <T> decoding(id: String, block: () -> T): Either<MemoryError, T> = try {
        block().right()
    } catch (e: IllegalArgumentException) {
        MemoryError.Malformed(id).left()
    }

    /** Reads the memory with [id], including a deleted one. */
    private fun readMemory(id: String): Either<MemoryError, Memory> = either {
        val document = persisting { database.getDocument(id) }.bind() ?: raise(MemoryError.NotFound(id))
        decoding(id) { document.toMemory() }.bind() ?: raise(MemoryError.NotFound(id))
    }

    /** Reads the memory with [id] only when it is active. */
    private fun readActive(id: String): Either<MemoryError, Memory> = either {
        val memory = readMemory(id).bind()
        ensure(!memory.deleted) { MemoryError.NotFound(id) }
        memory
    }

    private fun readAll(): Either<MemoryError, List<Memory>> = either {
        val rows = persisting { queryRows() }.bind()
        rows.map { row -> row.toListedMemory().bind() }
    }

    /** Reads every category master row ordered by id. A malformed row fails the whole read. */
    private fun readCategories(): Either<MemoryError, List<Category>> = either {
        val rows = persisting {
            QueryBuilder
                .select(
                    SelectResult.property(COLUMN_CATEGORY_ID),
                    SelectResult.property(COLUMN_CATEGORY_NAME),
                    SelectResult.property(COLUMN_CATEGORY_DESCRIPTION),
                )
                .from(DataSource.database(database))
                .where(Expression.property(KEY_TYPE).equalTo(Expression.string(TYPE_CATEGORY)))
                .orderBy(Ordering.property(COLUMN_CATEGORY_ID).ascending())
                .execute()
                .allResults()
        }.bind()
        rows.map { row ->
            val id = row.getInt(COLUMN_CATEGORY_ID)
            decoding(id.toString()) {
                Category(
                    id = id,
                    name = row.getString(COLUMN_CATEGORY_NAME).orEmpty(),
                    description = row.getString(COLUMN_CATEGORY_DESCRIPTION).orEmpty(),
                )
            }.bind()
        }
    }

    private fun Category.toDocument(): MutableDocument = MutableDocument(categoryDocumentId(id)).apply {
        setString(KEY_TYPE, TYPE_CATEGORY)
        setInt(COLUMN_CATEGORY_ID, this@toDocument.id)
        setString(COLUMN_CATEGORY_NAME, this@toDocument.name)
        setString(COLUMN_CATEGORY_DESCRIPTION, this@toDocument.description)
    }

    private fun queryRows(): List<Result> = QueryBuilder
        .select(
            SelectResult.expression(Meta.id).`as`(COLUMN_ID),
            SelectResult.property(COLUMN_CREATED_AT),
            SelectResult.property(COLUMN_TITLE),
            SelectResult.property(COLUMN_SUMMARY),
            SelectResult.property(COLUMN_TRANSCRIPTION),
            SelectResult.property(COLUMN_RECORDING_PATH),
            SelectResult.property(COLUMN_DELETED),
            SelectResult.property(COLUMN_TRANSCRIPTION_STATUS),
            SelectResult.property(COLUMN_TRANSCRIPTION_DONE),
            SelectResult.property(COLUMN_TRANSCRIPTION_ATTEMPTS),
            SelectResult.property(COLUMN_LAST_TRANSCRIPTION_ERROR),
            SelectResult.property(COLUMN_SUMMARY_STATUS),
            SelectResult.property(COLUMN_SUMMARY_ATTEMPTS),
            SelectResult.property(COLUMN_CATEGORIES),
        )
        .from(DataSource.database(database))
        .where(Expression.property(KEY_TYPE).equalTo(Expression.string(TYPE_MEMORY)))
        .orderBy(Ordering.property(COLUMN_CREATED_AT).descending())
        .execute()
        .allResults()

    /**
     * Gives memories saved before summary tracking existed their summary state, using
     * [migrateLegacySummaryState], and removes the legacy `summarized` key from every memory document.
     * Documents that already have a status keep it.
     */
    private fun migrateSummaryState(): Either<MemoryError, Unit> = persisting {
        val ids = QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`(COLUMN_ID))
            .from(DataSource.database(database))
            .where(Expression.property(KEY_TYPE).equalTo(Expression.string(TYPE_MEMORY)))
            .execute()
            .allResults()
            .mapNotNull { it.getString(COLUMN_ID) }

        database.inBatch {
            for (id in ids) {
                val document = database.getDocument(id) ?: continue
                val needsStatus = !document.contains(COLUMN_SUMMARY_STATUS)
                val hasLegacyBoolean = document.contains(COLUMN_SUMMARIZED)
                if (!needsStatus && !hasLegacyBoolean) continue
                val mutable = document.toMutable()
                if (needsStatus) {
                    val state = migrateLegacySummaryState(
                        summary = document.getString(COLUMN_SUMMARY).orEmpty(),
                        transcription = document.getString(COLUMN_TRANSCRIPTION).orEmpty(),
                        hasRecording = document.getString(COLUMN_RECORDING_PATH) != null,
                        legacyAttempts = document.getInt(COLUMN_SUMMARY_ATTEMPTS),
                    )
                    mutable
                        .setString(COLUMN_SUMMARY_STATUS, state.summaryStatus.name)
                        .setInt(COLUMN_SUMMARY_ATTEMPTS, state.summaryAttempts)
                }
                mutable.remove(COLUMN_SUMMARIZED)
                database.save(mutable)
            }
        }
    }

    private fun Result.toListedMemory(): Either<MemoryError, Memory> {
        val id = getString(COLUMN_ID) ?: return MemoryError.Malformed(id = "").left()
        return decoding(id) {
            Memory(
                id = id,
                createdAtEpochMillis = getLong(COLUMN_CREATED_AT),
                title = getString(COLUMN_TITLE).orEmpty(),
                summary = getString(COLUMN_SUMMARY).orEmpty(),
                transcription = getString(COLUMN_TRANSCRIPTION).orEmpty(),
                recordingPath = getString(COLUMN_RECORDING_PATH),
                deleted = getBoolean(COLUMN_DELETED),
                transcriptionStatus = transcriptionStatusOf(
                    getString(COLUMN_TRANSCRIPTION_STATUS),
                    getString(COLUMN_TRANSCRIPTION),
                    getString(COLUMN_RECORDING_PATH),
                    getBoolean(COLUMN_TRANSCRIPTION_DONE),
                ),
                transcriptionAttempts = getLong(COLUMN_TRANSCRIPTION_ATTEMPTS).toInt(),
                lastTranscriptionError = getString(COLUMN_LAST_TRANSCRIPTION_ERROR),
                summaryStatus = decodeSummaryStatus(getString(COLUMN_SUMMARY_STATUS)),
                summaryAttempts = getInt(COLUMN_SUMMARY_ATTEMPTS),
                categories = parseCategoriesString(getString(COLUMN_CATEGORIES)),
            )
        }
    }

    private fun Memory.toDocument(): MutableDocument = MutableDocument(id).apply {
        setString(KEY_TYPE, TYPE_MEMORY)
        setLong(COLUMN_CREATED_AT, createdAtEpochMillis)
        setString(COLUMN_TITLE, title)
        setString(COLUMN_SUMMARY, summary)
        setString(COLUMN_TRANSCRIPTION, transcription)
        setString(COLUMN_RECORDING_PATH, recordingPath)
        setBoolean(COLUMN_DELETED, deleted)
        setString(COLUMN_TRANSCRIPTION_STATUS, transcriptionStatus.name)
        setLong(COLUMN_TRANSCRIPTION_ATTEMPTS, transcriptionAttempts.toLong())
        setString(COLUMN_LAST_TRANSCRIPTION_ERROR, lastTranscriptionError)
        setString(COLUMN_SUMMARY_STATUS, summaryStatus.name)
        setInt(COLUMN_SUMMARY_ATTEMPTS, summaryAttempts)
        setString(COLUMN_CATEGORIES, categories.toCategoriesString())
    }

    private fun Document.toMemory(): Memory? {
        if (getString(KEY_TYPE) != TYPE_MEMORY) return null
        return Memory(
            id = id,
            createdAtEpochMillis = getLong(COLUMN_CREATED_AT),
            title = getString(COLUMN_TITLE).orEmpty(),
            summary = getString(COLUMN_SUMMARY).orEmpty(),
            transcription = getString(COLUMN_TRANSCRIPTION).orEmpty(),
            recordingPath = getString(COLUMN_RECORDING_PATH),
            deleted = getBoolean(COLUMN_DELETED),
            transcriptionStatus = transcriptionStatusOf(
                getString(COLUMN_TRANSCRIPTION_STATUS),
                getString(COLUMN_TRANSCRIPTION),
                getString(COLUMN_RECORDING_PATH),
                getBoolean(COLUMN_TRANSCRIPTION_DONE),
            ),
            transcriptionAttempts = getLong(COLUMN_TRANSCRIPTION_ATTEMPTS).toInt(),
            lastTranscriptionError = getString(COLUMN_LAST_TRANSCRIPTION_ERROR),
            summaryStatus = decodeSummaryStatus(getString(COLUMN_SUMMARY_STATUS)),
            summaryAttempts = getInt(COLUMN_SUMMARY_ATTEMPTS),
            categories = parseCategoriesString(getString(COLUMN_CATEGORIES)),
        )
    }

    /** Throws [IllegalArgumentException] for a missing or unknown status, which decodes as [MemoryError.Malformed]. */
    private fun decodeSummaryStatus(raw: String?): SummaryStatus =
        SummaryStatus.valueOf(requireNotNull(raw) { "Missing summaryStatus." })

    /**
     * Documents written before the status field existed have no value. A legacy document that finished
     * transcription (`transcriptionDone`) is DONE even when its transcript is blank. Otherwise a document with a
     * recording but no transcript is queued as [TranscriptionStatus.PENDING] so the recovery sweep picks it up.
     */
    private fun transcriptionStatusOf(
        stored: String?,
        transcription: String?,
        recordingPath: String?,
        legacyDone: Boolean,
    ): TranscriptionStatus =
        stored?.let { name -> TranscriptionStatus.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException(name) }
            ?: when {
                legacyDone -> TranscriptionStatus.DONE
                recordingPath != null && transcription.isNullOrBlank() -> TranscriptionStatus.PENDING
                else -> TranscriptionStatus.DONE
            }

    companion object {
        private const val KEY_TYPE = "type"
        private const val TYPE_MEMORY = "memory"
        private const val TYPE_CATEGORY = "category"
        private const val COLUMN_CATEGORY_ID = "id"
        private const val COLUMN_CATEGORY_NAME = "name"
        private const val COLUMN_CATEGORY_DESCRIPTION = "description"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CATEGORIES = "categories"
        private const val COLUMN_CREATED_AT = "createdAtEpochMillis"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_SUMMARY = "summary"
        private const val COLUMN_TRANSCRIPTION = "transcription"
        private const val COLUMN_RECORDING_PATH = "recordingPath"
        private const val COLUMN_DELETED = "deleted"
        private const val COLUMN_TRANSCRIPTION_STATUS = "transcriptionStatus"
        private const val COLUMN_TRANSCRIPTION_DONE = "transcriptionDone"
        private const val COLUMN_TRANSCRIPTION_ATTEMPTS = "transcriptionAttempts"
        private const val COLUMN_LAST_TRANSCRIPTION_ERROR = "lastTranscriptionError"
        private const val COLUMN_SUMMARY_STATUS = "summaryStatus"
        private const val COLUMN_SUMMARY_ATTEMPTS = "summaryAttempts"

        private fun categoryDocumentId(id: Int): String = "category:$id"

        /** The pre-1.4.2 stored boolean. Only the migration reads or removes it. */
        private const val COLUMN_SUMMARIZED = "summarized"

        /**
         * Opens the memory store on [database]. Existing memories are migrated to the summary
         * state and loaded before the service is returned, so [memories] is accurate from the start.
         */
        @OptIn(ExperimentalUuidApi::class)
        suspend fun open(
            database: Database,
            epochClock: EpochClock,
            idGenerator: () -> String = { Uuid.random().toString() },
        ): Either<MemoryError, KotbaseMemoryService> {
            val service = KotbaseMemoryService(
                database = database,
                epochClock = epochClock,
                idGenerator = idGenerator,
            )
            val migrated = withContext(Dispatchers.Default) { service.migrateSummaryState() }
            if (migrated.isLeft()) return migrated.map { service }
            return service.publishMemories().map { service }
        }
    }
}
