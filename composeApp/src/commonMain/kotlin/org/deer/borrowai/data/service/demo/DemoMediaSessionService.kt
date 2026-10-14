package org.deer.borrowai.data.service.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MutableMediaSessionState
import org.deer.borrowai.data.service.MutableSessionGeneration
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackRequestResult
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult

class DemoMediaSessionService(
    private val memoryService: MemoryService,
    private val scope: CoroutineScope,
) : MediaSessionService {
    private val mutableState = MutableMediaSessionState()
    private val mutableIdleTranscription = MutableStateFlow(INITIAL_TRANSCRIPTION)
    private val sessionGeneration = MutableSessionGeneration()
    private val closed = MutableStateFlow(false)
    private val tickerJob = MutableJob(scope)
    private val transcriptionJob = MutableJob(scope)

    override val state = mutableState.state
    override val idleTranscription = mutableIdleTranscription.asStateFlow()

    override fun startRecording(holdToRecord: Boolean) {
        ensureOpen()
        val generation = sessionGeneration.advance()
        mutableState.transitionRecording(holdToRecord = holdToRecord)
        startTicker(generation)
        startTranscription(generation)
    }

    override fun stopRecording(): RecordingResult? {
        ensureOpen()
        return mutableState.ifRecording { recording ->
            sessionGeneration.advance()
            tickerJob.cancel()
            transcriptionJob.cancel()
            mutableIdleTranscription.value = recording.transcription
            mutableState.transitionIdle()
            RecordingResult(recording.elapsedMillis, recording.transcription)
        }
    }

    override fun requestPlayback(target: PlaybackTarget): PlaybackRequestResult {
        ensureOpen()
        val memory = memoryService.memories.value.firstOrNull { it.id == target.memoryId }
            ?: return PlaybackRequestResult.MemoryNotFound

        return mutableState.ifRecording { recording ->
            mutableState.transitionRecording(
                elapsedMillis = recording.elapsedMillis,
                transcription = recording.transcription,
                pendingPlayback = target,
                holdToRecord = recording.holdToRecord,
            )
            PlaybackRequestResult.ConfirmationRequired(target)
        } ?: run {
            startPlayback(target, memory)
            PlaybackRequestResult.Started
        }
    }

    override fun confirmPendingPlayback(): PlaybackRequestResult {
        ensureOpen()
        val target = mutableState.ifRecording { it.pendingPlayback }
            ?: return PlaybackRequestResult.NoPendingConfirmation
        val memory = memoryService.memories.value.firstOrNull { it.id == target.memoryId }
        if (memory == null) {
            mutableState.ifRecording { recording ->
                mutableState.transitionRecording(
                    elapsedMillis = recording.elapsedMillis,
                    transcription = recording.transcription,
                    holdToRecord = recording.holdToRecord,
                )
            }
            return PlaybackRequestResult.MemoryNotFound
        }
        startPlayback(target, memory)
        return PlaybackRequestResult.Started
    }

    override fun cancelPendingPlayback(): Boolean {
        ensureOpen()
        return mutableState.ifRecording { recording ->
            if (recording.pendingPlayback == null) {
                false
            } else {
                mutableState.transitionRecording(
                    elapsedMillis = recording.elapsedMillis,
                    transcription = recording.transcription,
                    holdToRecord = recording.holdToRecord,
                )
                true
            }
        } ?: false
    }

    override fun stopPlayback() {
        ensureOpen()
        mutableState.ifPlaying {
            sessionGeneration.advance()
            tickerJob.cancel()
            mutableState.transitionIdle()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionGeneration.advance()
        tickerJob.cancel()
        transcriptionJob.cancel()
        mutableState.ifRecording { recording ->
            mutableIdleTranscription.value = recording.transcription
        }
        mutableState.transitionIdle()
    }

    private fun startPlayback(target: PlaybackTarget, memory: Memory) {
        val generation = sessionGeneration.advance()
        tickerJob.cancel()
        transcriptionJob.cancel()
        mutableState.ifRecording { recording ->
            mutableIdleTranscription.value = recording.transcription
        }
        val durationMillis = maxOf(MIN_PLAYBACK_DURATION_MILLIS, target.getText(memory).length * MILLIS_PER_CHARACTER)
        mutableState.transitionPlaying(target = target, durationMillis = durationMillis)
        startTicker(generation)
    }

    private fun startTicker(generation: Long) {
        tickerJob.launch {
            while (isActive && sessionGeneration.isCurrent(generation)) {
                delay(SESSION_TICK_INTERVAL_MILLIS)
                if (!isActive || !sessionGeneration.isCurrent(generation)) return@launch
                updateElapsedTime(generation)
                if (!sessionGeneration.isCurrent(generation) || mutableState.value == MediaSessionState.Idle) {
                    return@launch
                }
            }
        }
    }

    private fun startTranscription(generation: Long) {
        transcriptionJob.launch {
            var sampleIndex = 0
            while (isActive && sessionGeneration.isCurrent(generation)) {
                val sample = "${TRANSCRIPTION_SAMPLES[sampleIndex % TRANSCRIPTION_SAMPLES.size]} "
                for (character in sample) {
                    delay(TRANSCRIPTION_CHARACTER_INTERVAL_MILLIS)
                    if (!isActive || !sessionGeneration.isCurrent(generation)) return@launch
                    appendTranscription(generation, character)
                }
                sampleIndex += 1
            }
        }
    }

    internal val currentSessionGeneration: Long
        get() = sessionGeneration.value

    internal fun updateElapsedTime(generation: Long) {
        mutableState.update { current ->
            if (!sessionGeneration.isCurrent(generation)) {
                current
            } else {
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
        }
    }

    internal fun appendTranscription(generation: Long, character: Char) {
        mutableState.update { current ->
            if (sessionGeneration.isCurrent(generation) && current is MediaSessionState.Recording) {
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

    private fun ensureOpen() {
        check(!closed.value) { "Media session service has been closed." }
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
