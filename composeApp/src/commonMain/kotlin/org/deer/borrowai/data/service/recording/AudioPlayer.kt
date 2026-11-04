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
     * Resumes paused playback from its position. Throws [PlaybackUnavailableException] if playback cannot resume,
     * such as when another app holds the audio focus.
     */
    fun resume() {}

    /** Moves the position to [positionMillis]. The playing or paused state does not change. */
    fun seekTo(positionMillis: Long) {}

    /** The current position in milliseconds, or null when this player cannot report it. */
    fun positionMillis(): Long? = null

    /** True when [pause], [resume], [seekTo], and [positionMillis] work on this platform. */
    val controlsAvailable: Boolean
        get() = false

    /**
     * Registers a callback for when the platform stops playback on its own, such as when another app takes audio
     * focus. The callback runs after the player has already stopped.
     */
    fun setInterruptionHandler(handler: () -> Unit) {}
}

class PlaybackUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
