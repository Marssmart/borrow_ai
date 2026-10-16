package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import org.deer.borrowai.data.service.MemoryService

/**
 * Finishes deleting memories that were soft-deleted. For each deleted memory it removes the audio file
 * first, then the record. If the file cannot be removed, the record stays so the next run retries.
 * Runs at launch and then periodically.
 */
class DeletedMemoryPurger(
    private val memoryService: MemoryService,
    private val recordingsRoot: Path,
    private val isRecording: () -> Boolean,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    /** Purges deleted memories. Returns how many were removed. */
    suspend fun purge(): Int {
        if (isRecording()) return 0
        var purged = 0
        for (memory in memoryService.findAll()) {
            if (isRecording()) break
            if (!memory.deleted) continue
            try {
                val recordingPath = memory.recordingPath
                if (recordingPath != null) {
                    val file = recordingsRoot / recordingPath
                    if (fileSystem.exists(file)) fileSystem.delete(file)
                }
                if (memoryService.purge(memory.id)) purged++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // The record stays so the next run retries the audio removal.
            }
        }
        return purged
    }

    /** Runs [purge] now and then every [intervalMillis] while [scope] is active. */
    fun start(scope: CoroutineScope, intervalMillis: Long = DEFAULT_INTERVAL_MILLIS): Job = scope.launch {
        while (isActive) {
            purge()
            delay(intervalMillis)
        }
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 15 * 60 * 1000L
    }
}
