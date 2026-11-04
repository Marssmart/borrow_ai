package org.deer.borrowai.data.service.recording

/** Plays a single audio file from disk. Platform implementations decode AAC audio in an M4A container. */
interface AudioPlayer {
    /**
     * Starts playback of [absolutePath], replacing any playback already in progress. Returns the duration in
     * milliseconds. Throws [PlaybackUnavailableException] if the file cannot be played.
     */
    fun play(absolutePath: String): Long

    /** Stops playback if it is active. */
    fun stop()

    /** Pauses the active playback and keeps its position. Does nothing when nothing is playing. */
    fun pause() {}

    /**
     * Resumes paused playback from its position. Throws [AudioFocusHeldException] when another app holds the audio
     * focus, and [PlaybackUnavailableException] when playback cannot resume for another reason.
     */
    fun resume() {}

    /** Moves the position to [positionMillis]. The playing or paused state does not change. */
    fun seekTo(positionMillis: Long) {}

    /** The current position in milliseconds, or null when this player cannot report it. */
    fun positionMillis(): Long? = null

    /**
     * Sets the playback speed, where 1f is normal speed. The position and the playing or paused state do not change.
     * The speed stays for later playbacks on this player.
     */
    fun setSpeed(speed: Float) {}

    /** True when [pause], [resume], [seekTo], and [positionMillis] work on this platform. */
    val controlsAvailable: Boolean
        get() = false

    /**
     * Registers a callback for when the platform changes playback on its own, such as another app taking the audio
     * focus. The player has already paused or stopped when the callback runs. The player does not resume by itself.
     */
    fun setInterruptionHandler(handler: (PlaybackInterruption) -> Unit) {}
}

/** Why the platform changed playback without a request from the user. */
enum class PlaybackInterruption {
    /** Another app took the audio focus for a while. The player paused. [FOCUS_REGAINED] follows when it returns. */
    TRANSIENT,

    /** Another app took the audio focus for good. The player paused, and nothing resumes it by itself. */
    PERMANENT,

    /** The audio focus came back after a [TRANSIENT] loss. The player is still paused. */
    FOCUS_REGAINED,

    /** The audio output became noisy, such as when a headset was unplugged. The player paused. */
    OUTPUT_UNPLUGGED,
}

open class PlaybackUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Playback cannot start or resume because another app holds the audio focus. */
class AudioFocusHeldException : PlaybackUnavailableException("Audio focus is held by another app")
