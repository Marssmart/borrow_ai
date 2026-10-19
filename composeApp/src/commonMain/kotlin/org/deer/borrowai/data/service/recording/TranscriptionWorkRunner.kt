package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.CancellationException
import okio.Path
import org.deer.borrowai.data.service.MemoryService

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
) {
    /** Queues missing or unfinished transcriptions. Returns how many were requeued. Always schedules a drain so PENDING rows left by an earlier run are picked up. */
    suspend fun sweep(): Int {
        val queued = memoryService.requeueUnfinishedTranscriptions().fold(ifLeft = { return 0 }, ifRight = { it })
        scheduler.enqueueTranscription()
        return queued
    }

    /**
     * Transcribes PENDING memories one at a time. Returns false when the store failed or no transcriber is
     * available, so the job should be retried later. Returns true once nothing is pending.
     */
    suspend fun drain(): Boolean {
        val transcriber = batchTranscriber ?: return false
        var transcribed = 0
        try {
            while (true) {
                // Checked before claiming, so a missing model leaves the memory PENDING without using up an attempt.
                if (!transcriber.isAvailable()) return false
                val memory = memoryService.claimNextPendingTranscription()
                    .fold(ifLeft = { return false }, ifRight = { it }) ?: return true
                val relativePath = memory.recordingPath
                // A null or blank result is a failed attempt. Attempts are capped, so a broken model cannot loop forever.
                val transcript = if (relativePath == null) {
                    null
                } else {
                    try {
                        transcriber.transcribe(recordingsRoot / relativePath)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        null
                    }
                }
                if (transcript.isNullOrBlank()) {
                    memoryService.failTranscription(memory.id)
                } else {
                    memoryService.completeTranscription(memory.id, transcript)
                }
                transcribed++
            }
        } finally {
            if (transcribed > 0) onTranscriptionFinished()
        }
    }
}
