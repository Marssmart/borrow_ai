package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.background.BackgroundActivity
import org.deer.borrowai.data.service.background.BackgroundActivityTracker

/** Dispatches the background transcription jobs. Platforms provide the implementation (WorkManager on Android). */
interface TranscriptionScheduler {
    /** Requests that PENDING transcriptions are drained. */
    fun enqueueTranscription()

    /** Requests the sweep that requeues transcriptions left unfinished by an earlier run. */
    fun enqueueSweep()

    object None : TranscriptionScheduler {
        override fun enqueueTranscription() = Unit

        override fun enqueueSweep() = Unit
    }
}

/**
 * The work behind the two transcription jobs. [sweep] is the recovery job: it marks memories that need a transcript
 * as PENDING. [drain] is the transcription job: it transcribes PENDING memories until none are left.
 */
class TranscriptionWorkRunner(
    private val memoryService: MemoryService,
    private val recordingsRoot: Path,
    private val batchTranscriber: BatchTranscriber?,
    private val scheduler: TranscriptionScheduler,
    /** Called once a drain has transcribed at least one memory, so the summary job can pick up the new transcripts. */
    private val onTranscriptionFinished: () -> Unit = {},
    /** Called with the memory id after its transcript is saved. A failure there never fails the transcription. */
    private val onTranscriptionSucceeded: suspend (memoryId: String) -> Unit = {},
    private val activityTracker: BackgroundActivityTracker = BackgroundActivityTracker(),
) {
    /** Held for the whole of a drain. The sweep takes it too, so it never requeues rows a live drain is working on. */
    private val drainLock = Mutex()

    @Volatile
    private var stopped = false

    /**
     * Stops new sweeps and drains, then waits for a running drain to finish its current memory. Call before
     * closing the database, because a worker may still hold this runner after its owner is cleared.
     */
    suspend fun shutdown() {
        stopped = true
        drainLock.withLock { }
    }

    /**
     * Queues missing or unfinished transcriptions. Returns false when the store failed, so the job should be retried.
     * Waits for any running drain first, so IN_PROGRESS rows are only reset when no drain owns them.
     * On success, always schedules a drain so PENDING rows left by an earlier run are picked up.
     */
    suspend fun sweep(): Boolean = drainLock.withLock {
        // Checked under the lock, so a shutdown that starts after this check waits for the requeue to finish.
        if (stopped) return false
        memoryService.requeueUnfinishedTranscriptions().fold(ifLeft = { return false }, ifRight = { })
        scheduler.enqueueTranscription()
        true
    }

    /**
     * Transcribes PENDING memories one at a time. Returns false when the store failed or no transcriber is
     * available, so the job should be retried later. Returns true once nothing is pending.
     */
    suspend fun drain(): Boolean {
        val transcriber = batchTranscriber ?: return false
        return drainLock.withLock {
            if (stopped) return false
            drainPending(transcriber)
        }
    }

    private suspend fun drainPending(transcriber: BatchTranscriber): Boolean {
        var transcribed = 0
        try {
            while (true) {
                // Checked before claiming, so a missing model leaves the memory PENDING without using up an attempt.
                if (stopped || !transcriber.isAvailable()) return false
                val memory = memoryService.claimNextPendingTranscription()
                    .fold(ifLeft = { return false }, ifRight = { it }) ?: return true
                val relativePath = memory.recordingPath
                // A null or blank result is a failed attempt. Attempts are capped, so a broken model cannot loop forever.
                var failure: String? = null
                val transcript = if (relativePath == null) {
                    failure = "Recording file is missing"
                    null
                } else {
                    try {
                        activityTracker.track(BackgroundActivity.TRANSCRIPTION) {
                            transcriber.transcribe(
                                recordingsRoot / relativePath,
                                memory.durationMillis,
                                activityTracker::reportTranscriptionProgress,
                            )
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        failure = "${exception::class.simpleName}: ${exception.message}"
                        null
                    }
                }
                val written = if (transcript.isNullOrBlank()) {
                    memoryService.failTranscription(memory.id, failure ?: "Transcription returned no text")
                } else {
                    memoryService.completeTranscription(memory.id, transcript.trim())
                }
                // A failed write leaves the row IN_PROGRESS. Only the sweep requeues those rows, so schedule it;
                // a drain retry would skip them because it only claims PENDING rows.
                if (written.isLeft()) {
                    scheduler.enqueueSweep()
                    return false
                }
                if (!transcript.isNullOrBlank()) notifySucceeded(memory.id)
                transcribed++
            }
        } finally {
            if (transcribed > 0) onTranscriptionFinished()
        }
    }

    /** Runs the success callback. Its failure is dropped, because the transcript is already saved. */
    private suspend fun notifySucceeded(memoryId: String) {
        try {
            onTranscriptionSucceeded(memoryId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Intentionally empty: the transcription result does not depend on the callback.
        }
    }
}