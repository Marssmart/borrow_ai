package org.deer.borrowai.data.service.recording

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.MutableMediaSessionState
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
 * Playback of recorded media is not implemented yet.
 */
@OptIn(ExperimentalUuidApi::class)
class RecordingMediaSessionService(
    private val memoryService: MemoryService,
    private val recorder: AudioRecorder,
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

    override fun requestPlayback(target: PlaybackTarget): PlaybackRequestResult = playbackNotImplemented()

    override fun confirmPendingPlayback(): PlaybackRequestResult = playbackNotImplemented()

    override fun cancelPendingPlayback(): Boolean = false

    override fun stopPlayback() {
        ensureOpen()
    }

    override fun close() {
        if (closed.value) return
        closed.value = true
        pendingStartJob.cancel()
        finishRecording()
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

    private fun playbackNotImplemented(): Nothing {
        throw UnsupportedOperationException("Playback of recorded media is not implemented yet.")
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
