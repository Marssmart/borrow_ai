package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackRequestResult
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult

class DemoMediaSessionService(
    private val memoryService: MemoryService,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MediaSessionService {
    private val mutableState = MutableStateFlow<MediaSessionState>(MediaSessionState.Idle)
    private val mutableIdleTranscription = MutableStateFlow(INITIAL_TRANSCRIPTION)
    private var tickerJob: Job? = null
    private var transcriptionJob: Job? = null

    override val state = mutableState.asStateFlow()
    override val idleTranscription = mutableIdleTranscription.asStateFlow()

    override fun startRecording(holdToRecord: Boolean) {
        tickerJob?.cancel()
        transcriptionJob?.cancel()
        mutableState.value = MediaSessionState.Recording(
            elapsedMillis = 0L,
            transcription = "",
            holdToRecord = holdToRecord,
        )
        startTicker()
        startTranscription()
    }

    override fun stopRecording(): RecordingResult? {
        val recording = mutableState.value as? MediaSessionState.Recording ?: return null
        tickerJob?.cancel()
        tickerJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        mutableIdleTranscription.value = recording.transcription
        mutableState.value = MediaSessionState.Idle
        return RecordingResult(recording.elapsedMillis, recording.transcription)
    }

    override fun requestPlayback(target: PlaybackTarget): PlaybackRequestResult {
        val memory = memoryService.memories.value.firstOrNull { it.id == target.memoryId }
            ?: return PlaybackRequestResult.MemoryNotFound

        return when (val current = mutableState.value) {
            is MediaSessionState.Recording -> {
                mutableState.value = current.copy(pendingPlayback = target)
                PlaybackRequestResult.ConfirmationRequired(target)
            }

            else -> {
                startPlayback(target, memory)
                PlaybackRequestResult.Started
            }
        }
    }

    override fun confirmPendingPlayback(): PlaybackRequestResult {
        val recording = mutableState.value as? MediaSessionState.Recording
            ?: return PlaybackRequestResult.NoPendingConfirmation
        val target = recording.pendingPlayback
            ?: return PlaybackRequestResult.NoPendingConfirmation
        val memory = memoryService.memories.value.firstOrNull { it.id == target.memoryId }
        if (memory == null) {
            mutableState.value = recording.copy(pendingPlayback = null)
            return PlaybackRequestResult.MemoryNotFound
        }
        startPlayback(target, memory)
        return PlaybackRequestResult.Started
    }

    override fun cancelPendingPlayback(): Boolean {
        val recording = mutableState.value as? MediaSessionState.Recording ?: return false
        if (recording.pendingPlayback == null) return false
        mutableState.value = recording.copy(pendingPlayback = null)
        return true
    }

    override fun stopPlayback() {
        if (mutableState.value is MediaSessionState.Playing) {
            tickerJob?.cancel()
            tickerJob = null
            mutableState.value = MediaSessionState.Idle
        }
    }

    private fun startPlayback(target: PlaybackTarget, memory: Memory) {
        tickerJob?.cancel()
        transcriptionJob?.cancel()
        transcriptionJob = null
        (mutableState.value as? MediaSessionState.Recording)?.let { recording ->
            mutableIdleTranscription.value = recording.transcription
        }
        val playableText = when (target.content) {
            PlaybackContent.TRANSCRIPTION -> memory.transcription
            PlaybackContent.SUMMARY -> memory.summary
        }
        val durationMillis = maxOf(MIN_PLAYBACK_DURATION_MILLIS, playableText.length * MILLIS_PER_CHARACTER)
        mutableState.value = MediaSessionState.Playing(
            target = target,
            elapsedMillis = 0L,
            durationMillis = durationMillis,
        )
        startTicker()
    }

    private fun startTicker() {
        tickerJob = scope.launch {
            while (isActive) {
                delay(SESSION_TICK_INTERVAL_MILLIS)
                mutableState.update { current ->
                    when (current) {
                        is MediaSessionState.Recording -> current.copy(
                            elapsedMillis = current.elapsedMillis + SESSION_TICK_INTERVAL_MILLIS,
                        )

                        is MediaSessionState.Playing -> {
                            val nextElapsedMillis = current.elapsedMillis + SESSION_TICK_INTERVAL_MILLIS
                            if (nextElapsedMillis >= current.durationMillis) {
                                MediaSessionState.Idle
                            } else {
                                current.copy(elapsedMillis = nextElapsedMillis)
                            }
                        }

                        MediaSessionState.Idle -> current
                    }
                }
                if (mutableState.value == MediaSessionState.Idle) {
                    tickerJob = null
                    return@launch
                }
            }
        }
    }

    private fun startTranscription() {
        transcriptionJob = scope.launch {
            var sampleIndex = 0
            while (isActive) {
                val sample = "${TRANSCRIPTION_SAMPLES[sampleIndex % TRANSCRIPTION_SAMPLES.size]} "
                for (character in sample) {
                    delay(TRANSCRIPTION_CHARACTER_INTERVAL_MILLIS)
                    mutableState.update { current ->
                        if (current is MediaSessionState.Recording) {
                            val nextTranscription = current.transcription + character
                            current.copy(
                                transcription = if (nextTranscription.length > MAX_TRANSCRIPTION_LENGTH) {
                                    "…${nextTranscription.takeLast(MAX_TRANSCRIPTION_LENGTH - 1)}"
                                } else {
                                    nextTranscription
                                },
                            )
                        } else {
                            current
                        }
                    }
                }
                sampleIndex += 1
            }
        }
    }

    private companion object {
        const val SESSION_TICK_INTERVAL_MILLIS = 1_000L
        const val MIN_PLAYBACK_DURATION_MILLIS = 3_000L
        const val MILLIS_PER_CHARACTER = 35L
        const val TRANSCRIPTION_CHARACTER_INTERVAL_MILLIS = 45L
        const val MAX_TRANSCRIPTION_LENGTH = 2_000

        const val INITIAL_TRANSCRIPTION =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                "We should keep the first version focused, make the important details easy to find, " +
                "and leave room to refine the idea after trying it."

        val TRANSCRIPTION_SAMPLES = listOf(
            "The important part is making a little progress every day, even when the plan changes.",
            "We could revisit the first idea tomorrow and compare it with the simpler option.",
            "Remember to send the notes to the team before the afternoon review.",
            "A clear summary will make it easier to pick this conversation back up later.",
        )
    }
}
