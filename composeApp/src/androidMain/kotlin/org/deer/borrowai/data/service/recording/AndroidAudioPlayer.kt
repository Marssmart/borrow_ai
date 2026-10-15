package org.deer.borrowai.data.service.recording

import android.media.MediaPlayer

/** Plays recorded M4A files with [MediaPlayer]. */
class AndroidAudioPlayer : AudioPlayer {
    private var player: MediaPlayer? = null

    override fun play(absolutePath: String): Long {
        stop()
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(absolutePath)
            mediaPlayer.prepare()
            val durationMillis = mediaPlayer.duration.toLong()
            if (durationMillis <= 0) {
                throw PlaybackUnavailableException("Recording has no playable duration")
            }
            mediaPlayer.start()
            player = mediaPlayer
            return durationMillis
        } catch (exception: Exception) {
            mediaPlayer.release()
            throw PlaybackUnavailableException("Could not play recording", exception)
        }
    }

    override fun stop() {
        val mediaPlayer = player ?: return
        player = null
        mediaPlayer.release()
    }
}
