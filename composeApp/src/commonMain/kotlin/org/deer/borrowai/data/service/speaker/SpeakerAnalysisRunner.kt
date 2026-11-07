package org.deer.borrowai.data.service.speaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import arrow.core.Either
import arrow.core.right
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.SPEAKER_ANALYSIS_VERSION
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerSkipReason
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.SpeakerError
import org.deer.borrowai.data.service.SpeakerService
import org.deer.borrowai.data.service.background.BackgroundActivity
import org.deer.borrowai.data.service.background.BackgroundJobNames
import org.deer.borrowai.data.service.background.BackgroundJobQueue
import org.deer.borrowai.data.service.background.BackgroundActivityTracker

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
    private val activityTracker: BackgroundActivityTracker = BackgroundActivityTracker(),
    private val backgroundJobQueue: BackgroundJobQueue? = null,
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
        scope.launch {
            if (stopped) return@launch
            val queue = backgroundJobQueue
            if (queue == null) {
                drainSafely()
            } else {
                queue.enqueue(BackgroundJobNames.SPEAKER_ANALYSIS) { drainSafely() }
            }
        }
    }

    /**
     * Drains again whenever the speaker settings change. Turning the feature off marks waiting memories SKIPPED, and
     * turning it on starts analysis. Returns the job so the caller can stop it with the app graph.
     */
    fun watchSettings(): Job = scope.launch {
        var wasEnabled = speakerService.isFeatureEnabled().getOrNull()
        speakerService.changes.collect {
            val enabled = speakerService.isFeatureEnabled().getOrNull() ?: return@collect
            if (enabled != wasEnabled) {
                wasEnabled = enabled
                if (enabled) {
                    requeueSkippedAndRequestDrain()
                } else {
                    requestDrain()
                }
            }
        }
    }

    /**
     * Reconsiders saved unknown occurrences after speaker data changes, and once at launch. This only compares stored
     * embeddings; it never reopens recordings or changes manual/rejected links.
     */
    fun watchSpeakerChanges(): Job = scope.launch {
        enqueueRelinking()
        speakerService.changes.collect { enqueueRelinking() }
    }

    /** Requeues skipped work after recording or playback ends and the media session is no longer busy. */
    fun watchRecordingState(state: StateFlow<MediaSessionState>): Job = scope.launch {
        state.map { it is MediaSessionState.Recording || it is MediaSessionState.Playing }
            .distinctUntilChanged()
            .collect { busyState ->
                if (!busyState) {
                    while (!stopped && isRecording()) delay(MEDIA_SESSION_IDLE_CHECK_MS)
                    if (!stopped) requeueSkippedAndRequestDrain()
                }
            }
    }

    /** Requeues memories skipped while consent or model files were unavailable, then resumes the drain. */
    suspend fun requeueSkippedAndRequestDrain(): Either<MemoryError, Int> {
        if (stopped || isRecording() || !isEnabled() || !analyzer.isAvailable()) return 0.right()
        return memoryService.requeueSkippedSpeakerAnalyses().also { result ->
            if (result.isRight()) requestDrain()
        }
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
            if (!activityTracker.track(BackgroundActivity.SPEAKER_ANALYSIS) { analyzeClaimed(memory, library) }) return false
        }
    }

    private suspend fun relinkUnknownOccurrencesSafely() {
        try {
            relinkUnknownOccurrences()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // A later speaker-store change or app launch retries this pass.
        }
    }

    private suspend fun enqueueRelinking() {
        if (stopped) return
        val queue = backgroundJobQueue
        if (queue == null) {
            relinkUnknownOccurrencesSafely()
        } else {
            queue.enqueue(BackgroundJobNames.SPEAKER_RELINKING) { relinkUnknownOccurrencesSafely() }
        }
    }

    private suspend fun relinkUnknownOccurrences(): Boolean = drainLock.withLock {
        if (stopped || isRecording() || !isEnabled()) return@withLock false
        val library = speakerService.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION)
            .fold(ifLeft = { return@withLock false }, ifRight = { it })
        if (library.isEmpty()) return@withLock true
        val unknownEmbeddings = speakerService.findUnassignedSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION)
            .fold(ifLeft = { return@withLock false }, ifRight = { it })
        if (unknownEmbeddings.isEmpty()) return@withLock true
        val activeMemoryIds = memoryService.getState().value.mapTo(mutableSetOf()) { it.id }
        val occurrences = speakerService.findAllOccurrences()
            .fold(ifLeft = { return@withLock false }, ifRight = { it })
            .filter { it.memoryId in activeMemoryIds }
        val matches = planUnassignedOccurrenceMatches(
            occurrences = occurrences,
            unknownEmbeddings = unknownEmbeddings,
            library = library,
            modelVersion = SPEAKER_ANALYSIS_VERSION,
        )
        if (matches.isEmpty()) return@withLock true
        activityTracker.track(BackgroundActivity.SPEAKER_ANALYSIS) {
            for (match in matches) {
                if (stopped || isRecording() || !isEnabled()) return@track false
                if (speakerService.autoAssignUnassignedOccurrence(match.occurrenceId, match.speakerId).isLeft()) {
                    return@track false
                }
            }
            true
        }
    }

    /** A failed read counts as off, so no work starts without a known setting. */
    private suspend fun isEnabled(): Boolean =
        speakerService.isFeatureEnabled().fold(ifLeft = { false }, ifRight = { it })

    private companion object {
        const val MEDIA_SESSION_IDLE_CHECK_MS = 100L
    }

    /**
     * Analyzes one claimed memory and stores the result. Returns false when the drain must stop: a store write failed,
     * or analysis was switched off while this memory ran. A failed analysis uses up its attempt and returns true, so the
     * drain moves on to the next memory.
     */
    private suspend fun analyzeClaimed(memory: Memory, library: List<SpeakerEmbedding>): Boolean {
        val relativePath = memory.recordingPath ?: return skip(memory.id)
        val turns = try {
            analyzer.analyze(recordingsRoot / relativePath, shouldPause = { stopped || isRecording() })
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: SpeakerAnalysisPausedException) {
            if (memoryService.yieldSpeakerAnalysis(memory.id).isLeft()) return storeFailed()
            return false
        } catch (exception: Exception) {
            // The class name only: exception messages can carry file paths.
            return failAttempt(memory.id, exception::class.simpleName ?: "UnknownError")
        }
        var currentLibrary = library
        // A speaker deleted while the audio was analyzed makes the store refuse the write. The turns are re-planned once
        // against the current speakers. The audio is not analyzed again.
        for (attempt in 0..1) {
            if (attempt > 0) {
                currentLibrary = speakerService.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION)
                    .fold(ifLeft = { return failAttempt(memory.id, "Speaker data could not be read") }, ifRight = { it })
            }
            val known = speakerService.findSpeakers()
                .fold(ifLeft = { return failAttempt(memory.id, "Speaker data could not be read") }, ifRight = { it })
                .mapTo(mutableSetOf()) { it.id }
            val previous = speakerService.findOccurrences(memory.id)
                .fold(ifLeft = { return failAttempt(memory.id, "Speaker data could not be read") }, ifRight = { it })
            val drafts = try {
                planSpeakerOccurrences(
                    turns = turns,
                    library = currentLibrary,
                    modelVersion = SPEAKER_ANALYSIS_VERSION,
                    knownSpeakerIds = known,
                    previous = previous,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                return failAttempt(memory.id, exception::class.simpleName ?: "UnknownError")
            }
            if (drafts.isEmpty()) {
                // Rows left by an earlier partial attempt must not stay visible when this rebuild finds no speech.
                if (speakerService.deleteForMemory(memory.id).isLeft()) return storeFailed()
                return skip(memory.id, SpeakerSkipReason.NO_SPEECH)
            }
            val storeError = speakerService.replaceOccurrences(memory.id, SPEAKER_ANALYSIS_VERSION, drafts).leftOrNull()
            when (storeError) {
                null -> return complete(memory.id)
                SpeakerError.Disabled -> {
                    // Analysis was switched off while this memory ran. Its result is dropped and the drain stops.
                    skip(memory.id)
                    return false
                }
                is SpeakerError.NotFound -> continue
                else -> return failAttempt(memory.id, "Speaker occurrences could not be saved")
            }
        }
        return failAttempt(memory.id, "A speaker changed during analysis")
    }

    /** Marks the memory DONE. A memory deleted while it ran has its speaker rows removed here, as the purger may have run already. */
    private suspend fun complete(memoryId: String): Boolean {
        val completionError = memoryService.completeSpeakerAnalysis(memoryId, SPEAKER_ANALYSIS_VERSION).leftOrNull()
            ?: return true
        if (completionError !is MemoryError.NotFound) return storeFailed()
        return speakerService.deleteForMemory(memoryId).fold(ifLeft = { storeFailed() }, ifRight = { true })
    }

    private suspend fun skip(
        memoryId: String,
        reason: SpeakerSkipReason = SpeakerSkipReason.NOT_ANALYZED,
    ): Boolean = memoryService.skipSpeakerAnalysis(memoryId, reason)
        .fold(ifLeft = { storeFailed() }, ifRight = { true })

    private suspend fun failAttempt(memoryId: String, error: String): Boolean =
        memoryService.failSpeakerAnalysis(memoryId, error).fold(ifLeft = { storeFailed() }, ifRight = { true })

    /** A failed store write leaves its memory ANALYZING. The next drain requeues it, so this drain only stops. */
    private fun storeFailed(): Boolean {
        requestDrain()
        return false
    }
}
