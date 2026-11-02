package org.deer.borrowai.data.service.speaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.SPEAKER_ANALYSIS_VERSION
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.SpeakerError
import org.deer.borrowai.data.service.SpeakerService

/**
 * Runs speaker analysis one memory at a time. It follows [org.deer.borrowai.data.service.recording.TranscriptionWorkRunner]:
 * one drain under a lock, an attempt cap that ends in FAILED, and no work while a recording is active. Newest memories
 * are analyzed first. Drains run on [scope], so they stop with the app graph.
 */
class SpeakerAnalysisRunner(
    private val memoryService: MemoryService,
    private val speakerService: SpeakerService,
    private val analyzer: SpeakerAnalyzer,
    private val recordingsRoot: Path,
    private val isRecording: () -> Boolean,
    private val scope: CoroutineScope,
) {
    /** Held for a whole drain. Shutdown takes it too, so it waits for the drain in progress. */
    private val drainLock = Mutex()

    @Volatile
    private var stopped = false

    /**
     * Stops new drains, then waits for a running drain to finish its current memory. Call before closing the database,
     * because a drain may still hold this runner after its owner is cleared.
     */
    suspend fun shutdown() {
        stopped = true
        drainLock.withLock { }
    }

    /**
     * Called once a memory's transcript is saved. When the feature is off or no analyzer is available, the memory is
     * marked SKIPPED. Otherwise it stays PENDING and a drain starts.
     */
    suspend fun onTranscriptionSucceeded(memoryId: String) {
        if (stopped) return
        // A failed read leaves the memory PENDING. A later drain picks it up.
        val enabled = speakerService.isFeatureEnabled().getOrNull() ?: return
        if (enabled && analyzer.isAvailable()) {
            requestDrain()
        } else {
            memoryService.skipSpeakerAnalysis(memoryId)
        }
    }

    /**
     * Analyzes PENDING memories until none are left. Returns true when none are left, and false when the drain stopped
     * early: shutdown began, a recording started, the setting could not be read, analysis is off or the analyzer is
     * unavailable, or a store write failed. Analyses that an interrupted run left ANALYZING are requeued first. A drain
     * holds the lock, so none of those rows is in use.
     */
    suspend fun drain(): Boolean = drainLock.withLock {
        if (stopped) return@withLock false
        // Requeued before the checks below, so an interrupted analysis is never stranded while the feature is off.
        if (memoryService.requeueUnfinishedSpeakerAnalyses().isLeft()) return@withLock false
        if (isRecording()) return@withLock false
        // An unreadable setting is not treated as off. Otherwise a read failure would mark waiting memories SKIPPED.
        val enabled = speakerService.isFeatureEnabled().getOrNull() ?: return@withLock false
        if (!enabled || !analyzer.isAvailable()) {
            // Analysis cannot run, so waiting memories are marked SKIPPED. A failed mark is retried by the next drain.
            memoryService.skipPendingSpeakerAnalyses()
            return@withLock false
        }
        drainPending()
    }

    /** Starts a drain on [scope] without waiting for it. Called at launch and after a transcript is saved. */
    fun requestDrain() {
        scope.launch { drainSafely() }
    }

    /** An unexpected failure ends only this drain. Memories it left ANALYZING are requeued by the next drain. */
    private suspend fun drainSafely() {
        try {
            drain()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // Intentionally empty: the next drain retries the work.
        }
    }

    private suspend fun drainPending(): Boolean {
        while (true) {
            // Checked before each claim, so turning the feature off or starting a recording stops work between memories.
            if (stopped || isRecording() || !isEnabled() || !analyzer.isAvailable()) return false
            // Read before the claim, so a failed read leaves the memory PENDING with its attempts intact.
            val library = speakerService.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION)
                .fold(ifLeft = { return false }, ifRight = { it })
            val memory = memoryService.claimNextPendingSpeakerAnalysis()
                .fold(ifLeft = { return false }, ifRight = { it }) ?: return true
            if (!analyzeClaimed(memory, library)) return false
        }
    }

    /** A failed read counts as off, so no work starts without a known setting. */
    private suspend fun isEnabled(): Boolean =
        speakerService.isFeatureEnabled().fold(ifLeft = { false }, ifRight = { it })

    /**
     * Analyzes one claimed memory and stores the result. Returns false when the drain must stop: a store write failed,
     * or analysis was switched off while this memory ran. A failed analysis uses up its attempt and returns true, so the
     * drain moves on to the next memory.
     */
    private suspend fun analyzeClaimed(memory: Memory, library: List<SpeakerEmbedding>): Boolean {
        val relativePath = memory.recordingPath ?: return skip(memory.id)
        val drafts = try {
            planSpeakerOccurrences(
                turns = analyzer.analyze(recordingsRoot / relativePath),
                library = library,
                modelVersion = SPEAKER_ANALYSIS_VERSION,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // The class name only: exception messages can carry file paths.
            return failAttempt(memory.id, exception::class.simpleName ?: "UnknownError")
        }
        if (drafts.isEmpty()) return skip(memory.id)
        val storeError = speakerService.replaceOccurrences(memory.id, SPEAKER_ANALYSIS_VERSION, drafts).leftOrNull()
        if (storeError == SpeakerError.Disabled) {
            // Analysis was switched off while this memory ran. Its result is dropped and the drain stops.
            skip(memory.id)
            return false
        }
        if (storeError != null) return failAttempt(memory.id, "Speaker occurrences could not be saved")
        val completionError = memoryService.completeSpeakerAnalysis(memory.id, SPEAKER_ANALYSIS_VERSION).leftOrNull()
            ?: return true
        if (completionError !is MemoryError.NotFound) return storeFailed()
        // Deleted while it ran: the rows just written must go too, even if the purger already ran.
        return speakerService.deleteForMemory(memory.id).fold(ifLeft = { storeFailed() }, ifRight = { true })
    }

    private suspend fun skip(memoryId: String): Boolean =
        memoryService.skipSpeakerAnalysis(memoryId).fold(ifLeft = { storeFailed() }, ifRight = { true })

    private suspend fun failAttempt(memoryId: String, error: String): Boolean =
        memoryService.failSpeakerAnalysis(memoryId, error).fold(ifLeft = { storeFailed() }, ifRight = { true })

    /** A failed store write leaves its memory ANALYZING. The next drain requeues it, so this drain only stops. */
    private fun storeFailed(): Boolean {
        requestDrain()
        return false
    }
}
