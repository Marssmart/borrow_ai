package org.deer.borrowai.data.service.kotbase

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
import kotbase.SelectResult
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
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

    override suspend fun findById(id: String): Memory? = withContext(Dispatchers.Default) {
        database.getDocument(id)?.toMemory()
    }

    override suspend fun create(draft: MemoryDraft, id: String?): Memory = mutex.withLock {
        val memory = Memory(
            id = id ?: idGenerator(),
            createdAtEpochMillis = epochClock.nowEpochMillis(),
            title = draft.title,
            summary = draft.summary,
            transcription = draft.transcription,
            recordingPath = draft.recordingPath,
        )
        writeAndPublish {
            database.save(memory.toDocument())
        }
        memory
    }

    override suspend fun update(id: String, draft: MemoryDraft): Memory? = mutex.withLock {
        val existing = withContext(Dispatchers.Default) { database.getDocument(id)?.toMemory() }
            ?: return@withLock null
        val updated = existing.copy(
            title = draft.title,
            summary = draft.summary,
            transcription = draft.transcription,
            recordingPath = draft.recordingPath,
        )
        writeAndPublish {
            database.save(updated.toDocument())
        }
        updated
    }

    override suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(NonCancellable) {
            val deleted = withContext(Dispatchers.Default) {
                val document = database.getDocument(id)
                    ?.takeIf { it.getString(KEY_TYPE) == TYPE_MEMORY }
                    ?: return@withContext false
                database.delete(document)
                true
            }
            if (deleted) publishMemories()
            deleted
        }
    }

    /**
     * Runs [write] and refreshes the published state as one unit. Cancellation is deferred until
     * both finish, so a committed write never leaves [getState] stale.
     */
    private suspend fun writeAndPublish(write: () -> Unit) = withContext(NonCancellable) {
        withContext(Dispatchers.Default) { write() }
        publishMemories()
    }

    private suspend fun publishMemories() {
        memories.value = loadAll()
    }

    private suspend fun loadAll(): List<Memory> = withContext(Dispatchers.Default) {
        val query = QueryBuilder
            .select(
                SelectResult.expression(Meta.id).`as`(COLUMN_ID),
                SelectResult.property(COLUMN_CREATED_AT),
                SelectResult.property(COLUMN_TITLE),
                SelectResult.property(COLUMN_SUMMARY),
                SelectResult.property(COLUMN_TRANSCRIPTION),
                SelectResult.property(COLUMN_RECORDING_PATH),
            )
            .from(DataSource.database(database))
            .where(Expression.property(KEY_TYPE).equalTo(Expression.string(TYPE_MEMORY)))
            .orderBy(Ordering.property(COLUMN_CREATED_AT).descending())

        query.execute().allResults().map { row ->
            Memory(
                id = row.getString(COLUMN_ID)!!,
                createdAtEpochMillis = row.getLong(COLUMN_CREATED_AT),
                title = row.getString(COLUMN_TITLE).orEmpty(),
                summary = row.getString(COLUMN_SUMMARY).orEmpty(),
                transcription = row.getString(COLUMN_TRANSCRIPTION).orEmpty(),
                recordingPath = row.getString(COLUMN_RECORDING_PATH),
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

        /**
         * Opens the memory store on [database]. Existing memories are loaded before the service is
         * returned, so [memories] is accurate from the start.
         */
        @OptIn(ExperimentalUuidApi::class)
        suspend fun open(
            database: Database,
            epochClock: EpochClock,
            idGenerator: () -> String = { Uuid.random().toString() },
        ): KotbaseMemoryService {
            val service = KotbaseMemoryService(
                database = database,
                epochClock = epochClock,
                idGenerator = idGenerator,
            )
            service.publishMemories()
            return service
        }
    }
}
