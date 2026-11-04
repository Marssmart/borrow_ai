package org.deer.borrowai.data.service.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build

/** Plays recorded M4A files with [MediaPlayer], holding audio focus while a recording plays. */
class AndroidAudioPlayer(context: Context) : AudioPlayer {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var durationMillis = 0L
    // Set when the player reaches the end of the file or fails. The position then reads as the end.
    private var finished = false
    // Target of a seek the platform has not completed yet. Reads return it instead of the old position.
    private var pendingSeekMillis: Long? = null
    private var focusRequest: AudioFocusRequest? = null
    private var interruptionHandler: () -> Unit = {}

    override val controlsAvailable: Boolean = true

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_GAIN) return@OnAudioFocusChangeListener
        // Another app took focus, so stop and let the service reset its state.
        if (player != null) {
            stop()
            interruptionHandler()
        }
    }

    override fun play(absolutePath: String): Long {
        stop()
        if (!requestFocus()) {
            throw PlaybackUnavailableException("Audio focus is held by another app")
        }
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(absolutePath)
            mediaPlayer.prepare()
            val duration = mediaPlayer.duration.toLong()
            if (duration <= 0) {
                throw PlaybackUnavailableException("Recording has no playable duration")
            }
            mediaPlayer.setOnCompletionListener { finishPlayback(mediaPlayer) }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                finishPlayback(mediaPlayer)
                true
            }
            mediaPlayer.setOnSeekCompleteListener {
                if (player === mediaPlayer) pendingSeekMillis = null
            }
            mediaPlayer.start()
            player = mediaPlayer
            durationMillis = duration
            finished = false
            return duration
        } catch (exception: Exception) {
            mediaPlayer.release()
            abandonFocus()
            throw PlaybackUnavailableException("Could not play recording", exception)
        }
    }

    override fun stop() {
        val mediaPlayer = player ?: return
        player = null
        pendingSeekMillis = null
        mediaPlayer.release()
        abandonFocus()
    }

    override fun pause() {
        val mediaPlayer = player ?: return
        if (!finished) {
            try {
                mediaPlayer.pause()
            } catch (exception: IllegalStateException) {
                // The player is not in a state that can pause, so there is nothing left to pause.
            }
        }
        // Pausing releases the audio focus, so other apps can play while this recording is paused.
        abandonFocus()
    }

    override fun resume() {
        val mediaPlayer = player ?: throw PlaybackUnavailableException("No recording is loaded")
        if (!requestFocus()) {
            throw PlaybackUnavailableException("Audio focus is held by another app")
        }
        try {
            mediaPlayer.start()
        } catch (exception: IllegalStateException) {
            abandonFocus()
            throw PlaybackUnavailableException("Could not resume recording", exception)
        }
    }

    override fun seekTo(positionMillis: Long) {
        val mediaPlayer = player ?: return
        val target = positionMillis.coerceIn(0L, durationMillis)
        try {
            mediaPlayer.seekTo(target.toInt())
            pendingSeekMillis = target
            finished = false
        } catch (exception: IllegalStateException) {
            // The player cannot seek in its current state, so the position stays where it was.
        }
    }

    override fun positionMillis(): Long? {
        val mediaPlayer = player ?: return null
        if (finished) return durationMillis
        pendingSeekMillis?.let { return it }
        return try {
            mediaPlayer.currentPosition.toLong()
        } catch (exception: IllegalStateException) {
            null
        }
    }

    override fun setInterruptionHandler(handler: () -> Unit) {
        interruptionHandler = handler
    }

    private fun finishPlayback(finishedPlayer: MediaPlayer) {
        if (finishedPlayer !== player) return
        finished = true
        pendingSeekMillis = null
        // A finished recording is paused, so it gives up the audio focus like a paused one.
        abandonFocus()
    }

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            // Keep the request only when it was granted, so abandonFocus() never releases a request that was not held.
            focusRequest = request
            true
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }
}
