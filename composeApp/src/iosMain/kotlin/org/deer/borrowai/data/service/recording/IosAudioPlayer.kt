package org.deer.borrowai.data.service.recording

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.Foundation.NSURL

/** Plays recorded M4A files with [AVAudioPlayer]. */
@OptIn(ExperimentalForeignApi::class)
class IosAudioPlayer : AudioPlayer {
    private var player: AVAudioPlayer? = null

    override fun play(absolutePath: String): Long {
        stop()
        val created = try {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
            AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(absolutePath), error = null)
        } catch (exception: Exception) {
            throw PlaybackUnavailableException("Could not configure audio session", exception)
        } ?: throw PlaybackUnavailableException("Could not open recording")
        val durationSeconds = created.duration
        if (durationSeconds <= 0.0 || !created.prepareToPlay() || !created.play()) {
            throw PlaybackUnavailableException("Could not play recording")
        }
        player = created
        return (durationSeconds * MILLIS_PER_SECOND).toLong()
    }

    override fun stop() {
        val active = player ?: return
        player = null
        active.stop()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
