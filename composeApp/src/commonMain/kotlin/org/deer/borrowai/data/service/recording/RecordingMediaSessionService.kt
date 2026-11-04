package org.deer.borrowai.data.service.recording

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlin.math.ceil
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import okio.FileSystem
import okio.IOException
import okio.Path
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackRequest
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingError
import org.deer.borrowai.data.service.RecordingResult
import org.deer.borrowai.data.service.MutableJob

/**
 * Records microphone audio under [recordingsRoot] and saves a memory when recording stops.
 *
 * Recording starts only after [microphonePermission] is granted. Stopping before the grant arrives
 * cancels the pending start. Stored memories hold only a path relative to [recordingsRoot], because
 * absolute container paths change between installs and updates.
 *
 * Playback plays a memory's raw recording through [audioPlayer]. Recording and speaker sample targets have audio, so text
 * targets return [PlaybackError.RecordingUnavailable]. A speaker sample seeks to its start and stops at its end. It has
 * no pause, seek, or skip. Requesting playback while recording asks for confirmation. Confirming stops and saves the
 * recording, then plays the requested memory.
 */
@OptIn(ExperimentalUuidApi::class)
class RecordingMediaSessionService(
    private val memoryService: MemoryService,
    private val recorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val microphonePermission: MicrophonePermission,
    private val recordingsRoot: Path,
    private val scope: CoroutineScope,
    private val epochClock: EpochClock,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val idGenerator: () -> String = { Uuid.random().toString() },
    private val monotonicMillis: () -> Long = ::monotonicMillisSinceStart,
    private val liveTranscriber: LiveTranscriber? = null,
    private val transcriptionScheduler: TranscriptionScheduler = TranscriptionScheduler.None,
    private val durationReader: AudioDurationReader = AudioDurationReader.None,
) : MediaSessionService {
    private val mutableState = MutableStateFlow<MediaSessionState>(MediaSessionState.Idle)
    private val closed = MutableStateFlow(false)
    private val pendingStartJob = MutableJob(scope)
    private val tickerJob = MutableJob(scope)
    private val pendingSaves = MutableStateFlow<List<Job>>(emptyList())
    private var activeRecording: ActiveRecording? = null
    private val playbackSpeedState = MutableStateFlow(NORMAL_PLAYBACK_SPEED)
    // Set by a transient focus loss, so the focus returning resumes playback that was running.
    private var resumeAfterFocusReturns = false

    init {
        audioPlayer.setInterruptionHandler { interruption -> onPlaybackInterruption(interruption) }
        // The recorder reports errors on its own thread, so the state change is posted to the service's scope.
        recorder.setErrorListener { scope.launch { onRecorderError() } }
    }

    override val state: StateFlow<MediaSessionState> = mutableState.asStateFlow()
    // Cold, so it starts no coroutine: each collector reads the pending saves from its own scope.
    override val saving: Flow<Boolean> = pendingSaves.map { it.isNotEmpty() }.distinctUntilChanged()
    override val playbackSpeed: StateFlow<Float> = playbackSpeedState.asStateFlow()
    // Playback is decided per file in requestPlayback (missing, empty, or unplayable files return RecordingUnavailable),
    // so the service itself is always able to attempt playback.
    override val playbackAvailable: Boolean = true
    override val playbackControlsAvailable: Boolean
        get() = audioPlayer.controlsAvailable
    override val recordingPauseAvailable: Boolean
        get() = recorder.pauseAvailable
    override val microphoneDenial: StateFlow<MicrophoneDenial?>
        get() = microphonePermission.denial

    override fun openMicrophoneSettings() {
        microphonePermission.openSettings()
    }

    override fun startRecording(holdToRecord: Boolean) {
        ensureOpen()
        stopActivePlayback()
        if (mutableState.value !is MediaSessionState.Idle) return
        pendingStartJob.launch {
            if (!microphonePermission.request()) return@launch
            currentCoroutineContext().ensureActive()
            beginRecording(holdToRecord) { coroutineContext.isActive }
        }
    }

    override fun pauseRecording() {
        ensureOpen()
        val recording = activeRecording ?: return
        val current = recordingOrNull() ?: return
        // Hold-to-record has no pause control, so the service refuses it as well.
        if (current.paused || current.holdToRecord) return
        try {
            recorder.pause()
        } catch (exception: RecordingUnavailableException) {
            // The capture keeps running, so no audio is lost. The state stays Recording.
            return
        }
        runCatching { liveTranscriber?.pause() }
        val now = epochClock.nowEpochMillis()
        recording.pausedAtMillis = now
        mutableState.value = current.copy(paused = true, elapsedMillis = recording.activeElapsedMillis(now))
    }

    override fun resumeRecording() {
        ensureOpen()
        val recording = activeRecording ?: return
        val current = recordingOrNull() ?: return
        val pausedAt = recording.pausedAtMillis ?: return
        try {
            recorder.resume()
        } catch (exception: RecordingUnavailableException) {
            // The recording stays paused, so the user can resume again or save what was captured.
            return
        }
        runCatching { liveTranscriber?.resume() }
        val now = epochClock.nowEpochMillis()
        recording.pausedTotalMillis += now - pausedAt
        recording.pausedAtMillis = null
        mutableState.value = current.copy(paused = false, elapsedMillis = recording.activeElapsedMillis(now))
    }

    override fun stopRecording(): RecordingResult? {
        ensureOpen()
        pendingStartJob.cancel()
        return finishRecording()
    }

    override fun requestPlayback(target: PlaybackTarget): Either<PlaybackError, PlaybackRequest> {
        ensureOpen()
        // A recording failure stays visible until dismissed on the capture screen, so playback must not overwrite it.
        if (mutableState.value is MediaSessionState.Failed) return PlaybackError.RecordingUnavailable.left()
        val memory = findMemory(target) ?: return PlaybackError.MemoryNotFound.left()
        val absolutePath = playableRecordingPath(target, memory) ?: return PlaybackError.RecordingUnavailable.left()

        val recording = recordingOrNull() ?: return startPlayback(target, absolutePath)
        mutableState.value = recording.copy(pendingPlayback = target)
        return PlaybackRequest.ConfirmationRequired(target).right()
    }

    override fun confirmPendingPlayback(): Either<PlaybackError, PlaybackRequest> {
        ensureOpen()
        val target = recordingOrNull()?.pendingPlayback
            ?: return PlaybackError.NoPendingConfirmation.left()
        val memory = findMemory(target)
        val absolutePath = memory?.let { playableRecordingPath(target, it) }
        if (memory == null || absolutePath == null) {
            clearPendingPlayback()
            return (if (memory == null) PlaybackError.MemoryNotFound else PlaybackError.RecordingUnavailable).left()
        }
        // A failed save leaves Failed(SaveFailed) in place for the user to see, so playback must not overwrite it.
        if (finishRecording() == null && mutableState.value is MediaSessionState.Failed) {
            return PlaybackError.RecordingUnavailable.left()
        }
        return startPlayback(target, absolutePath)
    }

    override fun cancelPendingPlayback(): Boolean {
        ensureOpen()
        val recording = recordingOrNull() ?: return false
        if (recording.pendingPlayback == null) return false
        clearPendingPlayback()
        return true
    }

    override fun hasRecordingFile(memory: Memory): Boolean = recordingFile(memory) != null

    override fun stopPlayback() {
        ensureOpen()
        stopActivePlayback()
    }

    override fun pausePlayback() {
        ensureOpen()
        val playing = mutableState.value as? MediaSessionState.Playing ?: return
        if (playing.paused || !audioPlayer.controlsAvailable || playing.target.sample != null) return
        tickerJob.cancel()
        audioPlayer.pause()
        // A pause by the user ends any wait for the audio focus to return.
        resumeAfterFocusReturns = false
        mutableState.value = playing.copy(paused = true, elapsedMillis = currentPlaybackPosition(playing))
    }

    override fun resumePlayback(): Either<PlaybackError, Unit> {
        ensureOpen()
        val playing = mutableState.value as? MediaSessionState.Playing
        if (playing == null || !playing.paused || !audioPlayer.controlsAvailable) return Unit.right()
        return resumeFrom(playing)
    }

    override fun seekPlayback(positionMillis: Long) {
        ensureOpen()
        val playing = mutableState.value as? MediaSessionState.Playing ?: return
        if (!audioPlayer.controlsAvailable || playing.target.sample != null) return
        val target = positionMillis.coerceIn(0L, playing.durationMillis)
        audioPlayer.seekTo(target)
        mutableState.value = playing.copy(elapsedMillis = target)
    }

    override fun skipPlayback(deltaMillis: Long) {
        ensureOpen()
        val playing = mutableState.value as? MediaSessionState.Playing ?: return
        // The delta is clamped first, so an extreme value cannot overflow when it is added to the position.
        val clampedDeltaMillis = deltaMillis.coerceIn(-playing.durationMillis, playing.durationMillis)
        seekPlayback(currentPlaybackPosition(playing) + clampedDeltaMillis)
    }

    override fun setPlaybackSpeed(speed: Float) {
        ensureOpen()
        playbackSpeedState.value = speed
        audioPlayer.setSpeed(speed)
        mutableState.update { current ->
            if (current is MediaSessionState.Playing) current.copy(speed = speed) else current
        }
    }

    override fun dismissRecordingError() {
        mutableState.update { if (it is MediaSessionState.Failed) MediaSessionState.Idle else it }
    }

    override suspend fun awaitPendingWork() {
        while (true) {
            val job = pendingSaves.value.firstOrNull() ?: return
            job.join()
        }
    }

    override fun close() {
        if (closed.value) return
        closed.value = true
        pendingStartJob.cancel()
        finishRecording()
        stopActivePlayback()
    }

    private fun findMemory(target: PlaybackTarget) =
        memoryService.getState().value.firstOrNull { it.id == target.memoryId }

    /** Returns the absolute file path for a recording or speaker sample target, or null when no playable file exists. */
    private fun playableRecordingPath(target: PlaybackTarget, memory: Memory): Path? {
        if (target.content != PlaybackContent.RECORDING && target.content != PlaybackContent.SPEAKER_SAMPLE) return null
        return recordingFile(memory)
    }

    /** The absolute path of [memory]'s raw recording, or null when the file is missing. */
    private fun recordingFile(memory: Memory): Path? {
        val relativePath = memory.recordingPath?.takeIf { it.isNotBlank() } ?: return null
        val absolutePath = recordingsRoot / relativePath
        return absolutePath.takeIf { fileSystem.exists(it) }
    }

    private fun startPlayback(target: PlaybackTarget, absolutePath: Path): Either<PlaybackError, PlaybackRequest> {
        val sample = target.sample
        // A sample starts at its position. Without seeking it would play from the start of the recording instead.
        if (sample != null && !audioPlayer.controlsAvailable) return PlaybackError.RecordingUnavailable.left()
        val fileDurationMillis = try {
            audioPlayer.play(absolutePath.toString())
        } catch (exception: PlaybackUnavailableException) {
            // A failed replacement has already stopped any previous playback, so the state must not stay Playing.
            tickerJob.cancel()
            if (mutableState.value is MediaSessionState.Playing) mutableState.value = MediaSessionState.Idle
            return PlaybackError.RecordingUnavailable.left()
        }
        // Positions are absolute within the file. A sample stops at its end, or at the end of the file if it runs past it.
        val startMillis: Long
        val stopMillis: Long
        if (sample == null) {
            startMillis = 0L
            stopMillis = fileDurationMillis
        } else {
            if (sample.startMs >= fileDurationMillis) {
                tickerJob.cancel()
                audioPlayer.stop()
                if (mutableState.value is MediaSessionState.Playing) mutableState.value = MediaSessionState.Idle
                return PlaybackError.RecordingUnavailable.left()
            }
            startMillis = sample.startMs
            stopMillis = minOf(sample.endMs, fileDurationMillis)
            audioPlayer.seekTo(startMillis)
        }
        resumeAfterFocusReturns = false
        mutableState.value = MediaSessionState.Playing(
            target = target,
            elapsedMillis = startMillis,
            durationMillis = stopMillis,
            speed = playbackSpeedState.value,
        )
        // close() can run while audioPlayer.play blocks. It sets closed before stopping playback, so checking again
        // after publishing either sees the close or lets close stop this playback.
        if (closed.value) {
            stopActivePlayback()
            return PlaybackError.RecordingUnavailable.left()
        }
        // A memory saved before durations were stored learns its duration from its first play. recordDuration never overwrites one.
        if (findMemory(target)?.durationMillis == null) {
            scope.launch { memoryService.recordDuration(target.memoryId, fileDurationMillis) }
        }
        tickerJob.cancel()
        startPlaybackTicker(stopMillis, startMillis)
        return PlaybackRequest.Started.right()
    }

    private fun startPlaybackTicker(durationMillis: Long, startMillis: Long) {
        // The position comes from the player. A player that cannot report it falls back to a monotonic clock, so
        // changing the wall clock cannot stop or extend playback.
        val startedAtMillis = monotonicMillis()
        fun currentPosition(): Long = audioPlayer.positionMillis() ?: (startMillis + monotonicMillis() - startedAtMillis)
        tickerJob.launch {
            while (isActive) {
                // Wakes at the end when that comes first, so a speaker sample stops at its end and not a tick later.
                // Positions are in file time, so at a faster speed the end comes sooner in real time.
                val remainingMillis = durationMillis - currentPosition()
                val remainingRealMillis = ceil(remainingMillis / playbackSpeedState.value).toLong()
                delay(minOf(PLAYBACK_TICK_INTERVAL_MILLIS, remainingRealMillis.coerceAtLeast(0L)))
                val positionMillis = currentPosition()
                if (positionMillis >= durationMillis) {
                    finishPlayback()
                    return@launch
                }
                mutableState.update { current ->
                    if (current is MediaSessionState.Playing && !current.paused) {
                        current.copy(elapsedMillis = positionMillis)
                    } else {
                        current
                    }
                }
            }
        }
    }

    /**
     * Playback reached its end. A recording with controls stays paused there so Play can start it again. A speaker sample
     * stops instead, since it has nothing to resume.
     */
    private fun finishPlayback() {
        val playing = mutableState.value as? MediaSessionState.Playing
        if (!audioPlayer.controlsAvailable || playing?.target?.sample != null) {
            stopActivePlayback()
            return
        }
        mutableState.update { current ->
            if (current is MediaSessionState.Playing) {
                current.copy(paused = true, elapsedMillis = current.durationMillis)
            } else {
                current
            }
        }
    }

    /** The player's position, clamped to the recording. Falls back to the last published position when unavailable. */
    private fun currentPlaybackPosition(playing: MediaSessionState.Playing): Long =
        audioPlayer.positionMillis()?.coerceIn(0L, playing.durationMillis) ?: playing.elapsedMillis

    /**
     * Resumes [playing] from its paused position. A resume that another app's audio focus refuses leaves the memory
     * paused, marked as paused by another app, so the notice explains why.
     */
    private fun resumeFrom(playing: MediaSessionState.Playing): Either<PlaybackError, Unit> {
        // Playback paused at its end plays again from the beginning.
        val atEnd = playing.elapsedMillis >= playing.durationMillis
        val startMillis = if (atEnd) 0L else playing.elapsedMillis
        if (atEnd) audioPlayer.seekTo(startMillis)
        try {
            audioPlayer.resume()
        } catch (exception: AudioFocusHeldException) {
            if (atEnd) audioPlayer.seekTo(playing.durationMillis)
            mutableState.value = playing.copy(pausedByOtherApp = true)
            return PlaybackError.AudioFocusHeld.left()
        } catch (exception: PlaybackUnavailableException) {
            // A failed resume leaves the memory paused where it was, so the player goes back to the end.
            if (atEnd) audioPlayer.seekTo(playing.durationMillis)
            return PlaybackError.ResumeFailed.left()
        }
        resumeAfterFocusReturns = false
        mutableState.value = playing.copy(paused = false, pausedByOtherApp = false, elapsedMillis = startMillis)
        startPlaybackTicker(playing.durationMillis, startMillis)
        return Unit.right()
    }

    /**
     * Applies a change the platform made to playback. The player has already paused or stopped, so this updates the
     * state only. A transient loss resumes when the focus returns, but only if playback was running before the loss.
     * A speaker sample has no pause, so an interruption stops it instead.
     */
    private fun onPlaybackInterruption(interruption: PlaybackInterruption) {
        val playing = mutableState.value as? MediaSessionState.Playing ?: return
        if (playing.target.sample != null) {
            if (interruption != PlaybackInterruption.FOCUS_REGAINED) stopActivePlayback()
            return
        }
        when (interruption) {
            PlaybackInterruption.TRANSIENT -> if (!playing.paused) {
                resumeAfterFocusReturns = true
                pauseForInterruption(playing, pausedByOtherApp = true)
            }

            PlaybackInterruption.PERMANENT -> {
                resumeAfterFocusReturns = false
                if (!playing.paused) pauseForInterruption(playing, pausedByOtherApp = true)
            }

            PlaybackInterruption.OUTPUT_UNPLUGGED -> if (!playing.paused) {
                pauseForInterruption(playing, pausedByOtherApp = false)
            }

            PlaybackInterruption.FOCUS_REGAINED -> {
                if (playing.paused && playing.pausedByOtherApp && resumeAfterFocusReturns) resumeFrom(playing)
            }
        }
    }

    private fun pauseForInterruption(playing: MediaSessionState.Playing, pausedByOtherApp: Boolean) {
        tickerJob.cancel()
        mutableState.value = playing.copy(
            paused = true,
            pausedByOtherApp = pausedByOtherApp,
            elapsedMillis = currentPlaybackPosition(playing),
        )
    }

    private fun stopActivePlayback() {
        if (mutableState.value !is MediaSessionState.Playing) return
        tickerJob.cancel()
        audioPlayer.stop()
        mutableState.value = MediaSessionState.Idle
    }

    private fun clearPendingPlayback() {
        recordingOrNull()?.let { mutableState.value = it.copy(pendingPlayback = null) }
    }

    private fun recordingOrNull(): MediaSessionState.Recording? =
        mutableState.value as? MediaSessionState.Recording

    private fun beginRecording(holdToRecord: Boolean, isStartStillWanted: () -> Boolean) {
        if (closed.value || mutableState.value !is MediaSessionState.Idle || activeRecording != null) return
        val recordingId = idGenerator()
        val relativePath = recordingRelativePath(recordingId)
        val absolutePath = recordingsRoot / relativePath
        val temporaryPath = recordingsRoot / temporaryRecordingRelativePath(recordingId)
        try {
            temporaryPath.parent?.let { fileSystem.createDirectories(it, mustCreate = false) }
        } catch (exception: IOException) {
            // The recordings folder is not writable, so the capture cannot be saved. The microphone was never opened.
            mutableState.value = MediaSessionState.Failed(RecordingError.SaveFailed)
            return
        }
        try {
            recorder.start(temporaryPath.toString())
        } catch (exception: IOException) {
            // The recorder could not create its output file. The microphone is not the cause, so this is a save failure.
            failRecordingStart(temporaryPath, RecordingError.SaveFailed)
            return
        } catch (exception: RecordingUnavailableException) {
            failRecordingStart(temporaryPath, RecordingError.MicrophoneUnavailable)
            return
        }
        // stop or close can run while recorder.start blocks. Roll back the capture instead of publishing it.
        if (closed.value || !isStartStillWanted()) {
            runCatching { recorder.stop() }
            fileSystem.delete(temporaryPath, mustExist = false)
            return
        }
        activeRecording = ActiveRecording(
            recordingId = recordingId,
            relativePath = relativePath,
            absolutePath = absolutePath,
            temporaryPath = temporaryPath,
            startedAtMillis = epochClock.nowEpochMillis(),
        )
        mutableState.value = MediaSessionState.Recording(
            elapsedMillis = 0L,
            transcription = "",
            holdToRecord = holdToRecord,
        )
        startTicker()
        // Live transcription is best-effort. A failure keeps the recording running and is surfaced as state.
        liveTranscriber?.let { transcriber ->
            runCatching {
                transcriber.start(
                    onPartial = { text ->
                        mutableState.update { current ->
                            if (current is MediaSessionState.Recording) current.copy(transcription = text) else current
                        }
                    },
                    onFailure = { failure ->
                        mutableState.update { current ->
                            if (current is MediaSessionState.Recording) {
                                current.copy(liveTranscriptionFailure = failure)
                            } else {
                                current
                            }
                        }
                    },
                )
            }.onFailure {
                mutableState.update { current ->
                    if (current is MediaSessionState.Recording) {
                        current.copy(liveTranscriptionFailure = LiveTranscriptionFailure.ENGINE_FAILED)
                    } else {
                        current
                    }
                }
            }
        }
    }

    /**
     * Stops the active capture and saves its memory. Returns null when nothing was recording, or when the
     * recorder could not finalize the file. In that case the partial file is removed and no memory is saved.
     */
    private fun finishRecording(): RecordingResult? {
        val recording = activeRecording ?: return null
        activeRecording = null
        tickerJob.cancel()
        liveTranscriber?.let { transcriber -> runCatching { transcriber.stop() } }
        mutableState.value = MediaSessionState.Idle
        val elapsedMillis = recording.activeElapsedMillis(epochClock.nowEpochMillis())
        var savedDurationMillis: Long? = null
        // The save is registered as pending before the file is promoted, so RecordingReconciler never sees a
        // finished file whose memory is still being written.
        val save = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val created = memoryService.create(
                    draft = MemoryDraft(
                        title = "",
                        summary = "",
                        transcription = "",
                        recordingPath = recording.relativePath,
                        durationMillis = savedDurationMillis,
                    ),
                    id = recording.recordingId,
                    transcriptionStatus = TranscriptionStatus.PENDING,
                )
                // The audio stays on disk when the memory could not be created. RecordingReconciler pairs it on its next sweep.
                if (created.isRight()) transcriptionScheduler.enqueueTranscription()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // The audio stays on disk. RecordingReconciler pairs it with a memory on its next sweep.
            }
        }
        pendingSaves.update { it + save }
        save.invokeOnCompletion { pendingSaves.update { jobs -> jobs - save } }
        try {
            val stopFailure = runCatching { recorder.stop() }.exceptionOrNull()
            // After a recorder error, a partial file that fails to finalize is kept only when it has a readable duration.
            val partialDurationMillis = if (stopFailure != null && recording.interrupted) {
                readDuration(recording.temporaryPath)
            } else {
                null
            }
            if (stopFailure != null && partialDurationMillis == null) throw stopFailure
            fileSystem.atomicMove(recording.temporaryPath, recording.absolutePath)
            savedDurationMillis = partialDurationMillis ?: readDuration(recording.absolutePath)
        } catch (exception: Exception) {
            // Any finalize failure leaves no usable file. Cancelling the pending save keeps close() and busy checks from waiting on it.
            discardUnfinishedRecording(recording, save)
            mutableState.value = MediaSessionState.Failed(RecordingError.SaveFailed)
            return null
        }
        save.start()
        return RecordingResult(elapsedMillis = elapsedMillis, transcription = "", interrupted = recording.interrupted)
    }

    private fun readDuration(path: Path): Long? = runCatching { durationReader.durationMillis(path.toString()) }.getOrNull()

    private fun failRecordingStart(temporaryPath: Path, error: RecordingError) {
        deleteBestEffort(temporaryPath)
        mutableState.value = MediaSessionState.Failed(error)
    }

    private fun discardUnfinishedRecording(recording: ActiveRecording, save: Job) {
        save.cancel()
        deleteBestEffort(recording.temporaryPath)
        deleteBestEffort(recording.absolutePath)
    }

    // A cleanup failure must not skip publishing the recording error, so each delete is attempted independently.
    private fun deleteBestEffort(path: Path) {
        runCatching { fileSystem.delete(path, mustExist = false) }
    }

    /** True while a recording is in progress, being finalized, or waiting to be saved as a memory. A failure is not busy work. */
    val isBusy: Boolean
        get() {
            val state = mutableState.value
            return activeRecording != null || pendingSaves.value.isNotEmpty() ||
                (state !is MediaSessionState.Idle && state !is MediaSessionState.Failed)
        }

    /** Records a recorder error. The capture is kept, so stopping still saves the audio captured before the error. */
    private fun onRecorderError() {
        val recording = activeRecording ?: return
        recording.interrupted = true
        mutableState.update { current ->
            if (current is MediaSessionState.Recording) current.copy(interrupted = true) else current
        }
    }

    /** Active recording time: the wall clock since the start, minus every paused stretch, including the current one. */
    private fun ActiveRecording.activeElapsedMillis(nowMillis: Long): Long {
        val currentPauseMillis = pausedAtMillis?.let { nowMillis - it } ?: 0L
        return nowMillis - startedAtMillis - pausedTotalMillis - currentPauseMillis
    }

    private fun startTicker() {
        tickerJob.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                val recording = activeRecording ?: return@launch
                val elapsedMillis = recording.activeElapsedMillis(epochClock.nowEpochMillis())
                mutableState.update { current ->
                    if (current is MediaSessionState.Recording) current.copy(elapsedMillis = elapsedMillis) else current
                }
            }
        }
    }

    private fun ensureOpen() {
        check(!closed.value) { "Media session service has been closed." }
    }

    private class ActiveRecording(
        val recordingId: String,
        val relativePath: String,
        val absolutePath: Path,
        val temporaryPath: Path,
        val startedAtMillis: Long,
    ) {
        var pausedAtMillis: Long? = null
        var pausedTotalMillis: Long = 0L
        var interrupted: Boolean = false
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 1_000L
        const val PLAYBACK_TICK_INTERVAL_MILLIS = 250L
        const val NORMAL_PLAYBACK_SPEED = 1f
    }
}

private val monotonicOrigin = TimeSource.Monotonic.markNow()

internal fun monotonicMillisSinceStart(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds
