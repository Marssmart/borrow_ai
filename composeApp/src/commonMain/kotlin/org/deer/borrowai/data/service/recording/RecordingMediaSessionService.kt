package org.deer.borrowai.data.service.recording

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.MutableMediaSessionState
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackRequest
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult
import org.deer.borrowai.data.service.MutableJob

/**
 * Records microphone audio under [recordingsRoot] and saves a memory when recording stops.
 *
 * Recording starts only after [microphonePermission] is granted. Stopping before the grant arrives
 * cancels the pending start. Stored memories hold only a path relative to [recordingsRoot], because
 * absolute container paths change between installs and updates.
 *
 * Playback plays a memory's raw recording through [audioPlayer]. Only [PlaybackContent.RECORDING] targets have
 * audio, so text targets return [PlaybackError.RecordingUnavailable]. Requesting playback while
 * recording asks for confirmation. Confirming stops and saves the recording, then plays the requested memory.
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
) : MediaSessionService {
    private val mutableState = MutableMediaSessionState()
    private val closed = MutableStateFlow(false)
    private val pendingStartJob = MutableJob(scope)
    private val tickerJob = MutableJob(scope)
    private val pendingSaves = MutableStateFlow<List<Job>>(emptyList())
    private var activeRecording: ActiveRecording? = null

    init {
        audioPlayer.setInterruptionHandler { stopActivePlayback() }
    }

    override val state: StateFlow<MediaSessionState> = mutableState.state
    override val playbackAvailable: Boolean = true

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

    override fun stopRecording(): RecordingResult? {
        ensureOpen()
        pendingStartJob.cancel()
        return finishRecording()
    }

    override fun requestPlayback(target: PlaybackTarget): Either<PlaybackError, PlaybackRequest> {
        ensureOpen()
        val memory = findMemory(target) ?: return PlaybackError.MemoryNotFound.left()
        val absolutePath = playableRecordingPath(target, memory) ?: return PlaybackError.RecordingUnavailable.left()

        return mutableState.ifRecording { recording ->
            mutableState.transitionRecording(
                elapsedMillis = recording.elapsedMillis,
                transcription = recording.transcription,
                pendingPlayback = target,
                holdToRecord = recording.holdToRecord,
            )
            PlaybackRequest.ConfirmationRequired(target).right()
        } ?: startPlayback(target, absolutePath)
    }

    override fun confirmPendingPlayback(): Either<PlaybackError, PlaybackRequest> {
        ensureOpen()
        val target = mutableState.ifRecording { it.pendingPlayback }
            ?: return PlaybackError.NoPendingConfirmation.left()
        val memory = findMemory(target)
        val absolutePath = memory?.let { playableRecordingPath(target, it) }
        if (memory == null || absolutePath == null) {
            clearPendingPlayback()
            return (if (memory == null) PlaybackError.MemoryNotFound else PlaybackError.RecordingUnavailable).left()
        }
        finishRecording()
        return startPlayback(target, absolutePath)
    }

    override fun cancelPendingPlayback(): Boolean {
        ensureOpen()
        return mutableState.ifRecording { recording ->
            if (recording.pendingPlayback == null) {
                false
            } else {
                clearPendingPlayback()
                true
            }
        } ?: false
    }

    override fun stopPlayback() {
        ensureOpen()
        stopActivePlayback()
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

    /** Returns the absolute file path for a raw recording target, or null when no playable file exists. */
    private fun playableRecordingPath(target: PlaybackTarget, memory: Memory): Path? {
        if (target.content != PlaybackContent.RECORDING) return null
        val relativePath = memory.recordingPath?.takeIf { it.isNotBlank() } ?: return null
        val absolutePath = recordingsRoot / relativePath
        return absolutePath.takeIf { fileSystem.exists(it) }
    }

    private fun startPlayback(target: PlaybackTarget, absolutePath: Path): Either<PlaybackError, PlaybackRequest> {
        val durationMillis = try {
            audioPlayer.play(absolutePath.toString())
        } catch (exception: PlaybackUnavailableException) {
            // A failed replacement has already stopped any previous playback, so the state must not stay Playing.
            tickerJob.cancel()
            if (mutableState.value is MediaSessionState.Playing) mutableState.transitionIdle()
            return PlaybackError.RecordingUnavailable.left()
        }
        mutableState.transitionPlaying(target = target, durationMillis = durationMillis)
        // close() can run while audioPlayer.play blocks. It sets closed before stopping playback, so checking again
        // after publishing either sees the close or lets close stop this playback.
        if (closed.value) {
            stopActivePlayback()
            return PlaybackError.RecordingUnavailable.left()
        }
        tickerJob.cancel()
        startPlaybackTicker(durationMillis)
        return PlaybackRequest.Started.right()
    }

    private fun startPlaybackTicker(durationMillis: Long) {
        // Playback uses a monotonic clock so changing the wall clock cannot stop or extend playback.
        val startedAtMillis = monotonicMillis()
        tickerJob.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                val elapsedMillis = monotonicMillis() - startedAtMillis
                if (elapsedMillis >= durationMillis) {
                    stopActivePlayback()
                    return@launch
                }
                mutableState.update { current ->
                    if (current is MediaSessionState.Playing) current.copy(elapsedMillis = elapsedMillis) else current
                }
            }
        }
    }

    private fun stopActivePlayback() {
        if (mutableState.value !is MediaSessionState.Playing) return
        tickerJob.cancel()
        audioPlayer.stop()
        mutableState.transitionIdle()
    }

    private fun clearPendingPlayback() {
        mutableState.ifRecording { recording ->
            mutableState.transitionRecording(
                elapsedMillis = recording.elapsedMillis,
                transcription = recording.transcription,
                holdToRecord = recording.holdToRecord,
            )
        }
    }

    private fun beginRecording(holdToRecord: Boolean, isStartStillWanted: () -> Boolean) {
        if (closed.value || mutableState.value !is MediaSessionState.Idle || activeRecording != null) return
        val recordingId = idGenerator()
        val relativePath = recordingRelativePath(recordingId)
        val absolutePath = recordingsRoot / relativePath
        val temporaryPath = recordingsRoot / temporaryRecordingRelativePath(recordingId)
        try {
            temporaryPath.parent?.let { fileSystem.createDirectories(it, mustCreate = false) }
            recorder.start(temporaryPath.toString())
        } catch (exception: IOException) {
            fileSystem.delete(temporaryPath, mustExist = false)
            return
        } catch (exception: RecordingUnavailableException) {
            fileSystem.delete(temporaryPath, mustExist = false)
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
        mutableState.transitionRecording(holdToRecord = holdToRecord)
        startTicker()
    }

    /**
     * Stops the active capture and saves its memory. Returns null when nothing was recording, or when the
     * recorder could not finalize the file. In that case the partial file is removed and no memory is saved.
     */
    private fun finishRecording(): RecordingResult? {
        val recording = activeRecording ?: return null
        activeRecording = null
        tickerJob.cancel()
        mutableState.transitionIdle()
        val elapsedMillis = epochClock.nowEpochMillis() - recording.startedAtMillis
        // The save is registered as pending before the file is promoted, so RecordingReconciler never sees a
        // finished file whose memory is still being written.
        val save = scope.launch(start = CoroutineStart.LAZY) {
            try {
                memoryService.create(
                    draft = MemoryDraft(
                        title = "",
                        summary = "",
                        transcription = "",
                        recordingPath = recording.relativePath,
                    ),
                    id = recording.recordingId,
                ).fold(
                    // The audio stays on disk. RecordingReconciler pairs it with a memory on its next sweep.
                    ifLeft = { },
                    ifRight = { },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // The audio stays on disk. RecordingReconciler pairs it with a memory on its next sweep.
            }
        }
        pendingSaves.update { it + save }
        save.invokeOnCompletion { pendingSaves.update { jobs -> jobs - save } }
        try {
            recorder.stop()
            fileSystem.atomicMove(recording.temporaryPath, recording.absolutePath)
        } catch (exception: RecordingUnavailableException) {
            discardUnfinishedRecording(recording, save)
            return null
        } catch (exception: IOException) {
            discardUnfinishedRecording(recording, save)
            return null
        }
        save.start()
        return RecordingResult(elapsedMillis = elapsedMillis, transcription = "")
    }

    private fun discardUnfinishedRecording(recording: ActiveRecording, save: Job) {
        save.cancel()
        fileSystem.delete(recording.temporaryPath, mustExist = false)
        fileSystem.delete(recording.absolutePath, mustExist = false)
    }

    /** True while a recording is in progress, being finalized, or waiting to be saved as a memory. */
    val isBusy: Boolean
        get() = activeRecording != null || pendingSaves.value.isNotEmpty() || mutableState.value !is MediaSessionState.Idle

    private fun startTicker() {
        tickerJob.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                val recording = activeRecording ?: return@launch
                val elapsedMillis = epochClock.nowEpochMillis() - recording.startedAtMillis
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
    )

    private companion object {
        const val TICK_INTERVAL_MILLIS = 1_000L
    }
}

private val monotonicOrigin = TimeSource.Monotonic.markNow()

internal fun monotonicMillisSinceStart(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds
