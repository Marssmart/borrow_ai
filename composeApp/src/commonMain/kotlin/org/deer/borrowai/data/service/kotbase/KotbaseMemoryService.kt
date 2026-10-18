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
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
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

    override suspend fun create(draft: MemoryDraft, id: String?): Either<MemoryError, Memory> = mutex.withLock {
        val memory = Memory(
            id = id ?: idGenerator(),
            createdAtEpochMillis = epochClock.nowEpochMillis(),
            title = draft.title,
            summary = draft.summary,
            transcription = draft.transcription,
            recordingPath = draft.recordingPath,
        )
        writeAndPublish { database.save(memory.toDocument()) }.map { memory }
    }

    override suspend fun update(id: String, draft: MemoryDraft): Either<MemoryError, Unit> = mutex.withLock {
        either {
            val existing = withContext(Dispatchers.Default) { readActive(id) }.bind()
            val updated = existing.copy(
                title = draft.title,
                summary = draft.summary,
                transcription = draft.transcription,
                recordingPath = draft.recordingPath,
            )
            writeAndPublish { database.save(updated.toDocument()) }.bind()
        }
    }

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

    private fun queryRows(): List<Result> = QueryBuilder
        .select(
            SelectResult.expression(Meta.id).`as`(COLUMN_ID),
            SelectResult.property(COLUMN_CREATED_AT),
            SelectResult.property(COLUMN_TITLE),
            SelectResult.property(COLUMN_SUMMARY),
            SelectResult.property(COLUMN_TRANSCRIPTION),
            SelectResult.property(COLUMN_RECORDING_PATH),
            SelectResult.property(COLUMN_DELETED),
        )
        .from(DataSource.database(database))
        .where(Expression.property(KEY_TYPE).equalTo(Expression.string(TYPE_MEMORY)))
        .orderBy(Ordering.property(COLUMN_CREATED_AT).descending())
        .execute()
        .allResults()

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
        )
    }

    companion object {
        private const val KEY_TYPE = "type"
        private const val TYPE_MEMORY = "memory"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CREATED_AT = "createdAtEpochMillis"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_SUMMARY = "summary"
        private const val COLUMN_TRANSCRIPTION = "transcription"
        private const val COLUMN_RECORDING_PATH = "recordingPath"
        private const val COLUMN_DELETED = "deleted"

        /**
         * Opens the memory store on [database]. Existing memories are loaded before the service is
         * returned, so [memories] is accurate from the start.
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
            return service.publishMemories().map { service }
        }
    }
}
