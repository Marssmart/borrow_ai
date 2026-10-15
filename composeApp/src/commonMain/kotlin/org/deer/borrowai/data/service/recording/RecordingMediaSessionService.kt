package org.deer.borrowai.data.service.recording

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.MutableMediaSessionState
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackRequestResult
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult
import org.deer.borrowai.data.service.demo.MutableJob

/**
 * Records microphone audio under [recordingsRoot] and saves a memory when recording stops.
 *
 * Recording starts only after [microphonePermission] is granted. Stopping before the grant arrives
 * cancels the pending start. Stored memories hold only a path relative to [recordingsRoot], because
 * absolute container paths change between installs and updates.
 *
 * Playback plays a memory's raw recording through [audioPlayer]. Only [PlaybackContent.RECORDING] targets have
 * audio, so text targets return [PlaybackRequestResult.RecordingUnavailable]. Requesting playback while
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
) : MediaSessionService {
    private val mutableState = MutableMediaSessionState()
    private val mutableIdleTranscription = MutableStateFlow("")
    private val closed = MutableStateFlow(false)
    private val pendingStartJob = MutableJob(scope)
    private val tickerJob = MutableJob(scope)
    private var activeRecording: ActiveRecording? = null

    override val state: StateFlow<MediaSessionState> = mutableState.state
    override val idleTranscription: StateFlow<String> = mutableIdleTranscription.asStateFlow()

    override fun startRecording(holdToRecord: Boolean) {
        ensureOpen()
        if (mutableState.value !is MediaSessionState.Idle) return
        pendingStartJob.launch {
            if (!microphonePermission.request()) return@launch
            beginRecording(holdToRecord)
        }
    }

    override fun stopRecording(): RecordingResult? {
        ensureOpen()
        pendingStartJob.cancel()
        return finishRecording()
    }

    override fun requestPlayback(target: PlaybackTarget): PlaybackRequestResult {
        ensureOpen()
        val memory = findMemory(target) ?: return PlaybackRequestResult.MemoryNotFound
        val absolutePath = playableRecordingPath(target, memory) ?: return PlaybackRequestResult.RecordingUnavailable

        return mutableState.ifRecording { recording ->
            mutableState.transitionRecording(
                elapsedMillis = recording.elapsedMillis,
                transcription = recording.transcription,
                pendingPlayback = target,
                holdToRecord = recording.holdToRecord,
            )
            PlaybackRequestResult.ConfirmationRequired(target)
        } ?: startPlayback(target, absolutePath)
    }

    override fun confirmPendingPlayback(): PlaybackRequestResult {
        ensureOpen()
        val target = mutableState.ifRecording { it.pendingPlayback }
            ?: return PlaybackRequestResult.NoPendingConfirmation
        val memory = findMemory(target)
        val absolutePath = memory?.let { playableRecordingPath(target, it) }
        if (memory == null || absolutePath == null) {
            clearPendingPlayback()
            return if (memory == null) PlaybackRequestResult.MemoryNotFound else PlaybackRequestResult.RecordingUnavailable
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

    private fun startPlayback(target: PlaybackTarget, absolutePath: Path): PlaybackRequestResult {
        val durationMillis = try {
            audioPlayer.play(absolutePath.toString())
        } catch (exception: PlaybackUnavailableException) {
            return PlaybackRequestResult.RecordingUnavailable
        }
        tickerJob.cancel()
        mutableState.transitionPlaying(target = target, durationMillis = durationMillis)
        startPlaybackTicker(durationMillis)
        return PlaybackRequestResult.Started
    }

    private fun startPlaybackTicker(durationMillis: Long) {
        val startedAtMillis = epochClock.nowEpochMillis()
        tickerJob.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                val elapsedMillis = epochClock.nowEpochMillis() - startedAtMillis
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

    private fun beginRecording(holdToRecord: Boolean) {
        if (mutableState.value !is MediaSessionState.Idle || activeRecording != null) return
        val recordingId = idGenerator()
        val relativePath = recordingRelativePath(recordingId)
        val absolutePath = recordingsRoot / relativePath
        absolutePath.parent?.let { fileSystem.createDirectories(it, mustCreate = false) }
        try {
            recorder.start(absolutePath.toString())
        } catch (exception: RecordingUnavailableException) {
            fileSystem.delete(absolutePath, mustExist = false)
            return
        }
        activeRecording = ActiveRecording(
            relativePath = relativePath,
            absolutePath = absolutePath,
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
        try {
            recorder.stop()
        } catch (exception: RecordingUnavailableException) {
            fileSystem.delete(recording.absolutePath, mustExist = false)
            return null
        }
        scope.launch {
            memoryService.create(
                MemoryDraft(
                    title = "",
                    summary = "",
                    transcription = "",
                    recordingPath = recording.relativePath,
                ),
            )
        }
        return RecordingResult(elapsedMillis = elapsedMillis, transcription = "")
    }

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
        val relativePath: String,
        val absolutePath: Path,
        val startedAtMillis: Long,
    )

    private companion object {
        const val TICK_INTERVAL_MILLIS = 1_000L
    }
}
