package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.service.MemoryService

/**
 * Pairs raw recordings with memories. A recording file is named `<id>.m4a`, and its memory is saved with
 * the same `<id>`. A file with no matching memory was left behind (for example, the save failed or the app
 * was killed), so this creates the missing memory. Runs at launch and then periodically.
 */
class RecordingReconciler(
    private val memoryService: MemoryService,
    private val recordingsRoot: Path,
    private val isRecording: () -> Boolean,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    /** Creates memories for orphaned recordings. Returns how many were created. */
    suspend fun reconcile(): Int {
        if (isRecording()) return 0
        val recordingsDirectory = recordingsRoot / RECORDINGS_DIRECTORY
        if (!fileSystem.exists(recordingsDirectory)) return 0
        val memories = memoryService.getState().value
        val pairedIds = memories.mapTo(mutableSetOf()) { it.id }
        val pairedPaths = memories.mapNotNullTo(mutableSetOf()) { it.recordingPath }
        var created = 0
        val files = fileSystem.list(recordingsDirectory)
        for (file in files) {
            if (isRecording()) break
            val name = file.name
            if (!name.endsWith(".$RECORDING_EXTENSION")) continue
            // Unfinished captures are written under a temporary prefix until they are finalized.
            if (name.startsWith(TEMPORARY_RECORDING_PREFIX)) continue
            val id = name.removeSuffix(".$RECORDING_EXTENSION")
            val relativePath = recordingRelativePath(id)
            if (id in pairedIds || relativePath in pairedPaths) continue
            try {
                // Re-check, because a save may have completed after the snapshot above was taken.
                if (memoryService.findById(id) != null) continue
                memoryService.create(
                    draft = MemoryDraft(
                        title = "",
                        summary = "",
                        transcription = "",
                        recordingPath = relativePath,
                    ),
                    id = id,
                )
                created++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // The file stays on disk and the next sweep retries it.
            }
        }
        return created
    }

    /** Runs [reconcile] now and then every [intervalMillis] while [scope] is active. */
    fun start(scope: CoroutineScope, intervalMillis: Long = DEFAULT_INTERVAL_MILLIS): Job = scope.launch {
        while (isActive) {
            reconcile()
            delay(intervalMillis)
        }
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 15 * 60 * 1000L
    }
}
