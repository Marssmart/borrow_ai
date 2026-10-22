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
    private var focusRequest: AudioFocusRequest? = null
    private var interruptionHandler: () -> Unit = {}

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
            val durationMillis = mediaPlayer.duration.toLong()
            if (durationMillis <= 0) {
                throw PlaybackUnavailableException("Recording has no playable duration")
            }
            mediaPlayer.start()
            player = mediaPlayer
            return durationMillis
        } catch (exception: Exception) {
            mediaPlayer.release()
            abandonFocus()
            throw PlaybackUnavailableException("Could not play recording", exception)
        }
    }

    override fun stop() {
        val mediaPlayer = player ?: return
        player = null
        mediaPlayer.release()
        abandonFocus()
    }

    override fun setInterruptionHandler(handler: () -> Unit) {
        interruptionHandler = handler
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
