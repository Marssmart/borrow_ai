package org.deer.borrowai.data.service.recording

/**
 * Produces a live transcript alongside a recording. It captures its own audio, separate from the recorder that saves
 * the memory file. Implementations must never throw into the recording flow: when live transcription cannot run or
 * stops early, they report a [LiveTranscriptionFailure] and the recording continues normally.
 */
interface LiveTranscriber {
    /**
     * Starts capturing. [onPartial] receives the full current transcript and may be called from a background thread.
     * [onFailure] is called at most once per session, from a background thread, when live transcription cannot start
     * or stops early. It is never called after [stop] returns.
     */
    fun start(onPartial: (String) -> Unit, onFailure: (LiveTranscriptionFailure) -> Unit)

    /** Stops capturing. After this returns, [onPartial] and [onFailure] are never called again. */
    fun stop()
}

/** Why live transcription could not run or stopped early. The saved recording is never affected. */
enum class LiveTranscriptionFailure {
    /** The speech model or native runtime is missing or could not be verified. */
    MODEL_UNAVAILABLE,

    /** The speech recognizer failed to initialize. */
    ENGINE_FAILED,

    /** The microphone could not be opened for live capture. */
    MICROPHONE_UNAVAILABLE,

    /** Audio input stopped delivering samples during a session. */
    AUDIO_INTERRUPTED,

    /** A decode pass failed, so live text stopped. */
    TRANSCRIPTION_FAILED,
}

/**
 * Fixed-capacity mono sample window. Keeps the most recent [capacity] samples so each decode sees bounded audio.
 */
class LiveAudioWindow(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val samples = FloatArray(capacity)
    private var nextIndex = 0
    private var filled = 0

    val size: Int get() = filled

    /** Appends the first [count] PCM16 samples, scaled to the [-1, 1] float range. */
    fun appendPcm16(pcm: ShortArray, count: Int = pcm.size) {
        for (i in 0 until count) {
            samples[nextIndex] = pcm[i] / PCM16_SCALE
            nextIndex = (nextIndex + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** Returns the buffered samples in chronological order. */
    fun snapshot(): FloatArray {
        val result = FloatArray(filled)
        val start = if (filled < capacity) 0 else nextIndex
        for (i in 0 until filled) {
            result[i] = samples[(start + i) % capacity]
        }
        return result
    }

    fun clear() {
        nextIndex = 0
        filled = 0
    }

    private companion object {
        const val PCM16_SCALE = 32768f
    }
}
