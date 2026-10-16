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

    /**
     * Registers a callback for when the platform stops playback on its own, such as when another app takes audio
     * focus. The callback runs after the player has already stopped.
     */
    fun setInterruptionHandler(handler: () -> Unit) {}
}

class PlaybackUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
