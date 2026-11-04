package org.deer.borrowai.data.service.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build

/**
 * Plays recorded M4A files with [MediaPlayer], holding audio focus while a recording plays. Another app taking the
 * focus, and an unplugged headset, pause playback and are reported through the interruption handler.
 */
class AndroidAudioPlayer(context: Context) : AudioPlayer {
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var durationMillis = 0L
    // Set when the player reaches the end of the file or fails. The position then reads as the end.
    private var finished = false
    // Target of a seek the platform has not completed yet. Reads return it instead of the old position.
    private var pendingSeekMillis: Long? = null
    // Kept across playbacks, so each memory plays at the speed the user chose.
    private var speed = NORMAL_SPEED
    // Set after a transient focus loss. The request stays registered, so the platform reports when the focus returns.
    private var awaitingFocusGain = false
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiverRegistered = false
    private var interruptionHandler: (PlaybackInterruption) -> Unit = {}

    override val controlsAvailable: Boolean = true

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            val mediaPlayer = player ?: return
            if (!isPlaying(mediaPlayer)) return
            pausePlayer(mediaPlayer)
            abandonFocus()
            interruptionHandler(PlaybackInterruption.OUTPUT_UNPLUGGED)
        }
    }

    override fun play(absolutePath: String): Long {
        stop()
        if (!requestFocus()) {
            throw AudioFocusHeldException()
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
            applySpeed(mediaPlayer)
            mediaPlayer.start()
            registerNoisyReceiver()
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
        unregisterNoisyReceiver()
        mediaPlayer.release()
        abandonFocus()
    }

    override fun pause() {
        val mediaPlayer = player ?: return
        pausePlayer(mediaPlayer)
        // Pausing releases the audio focus, so other apps can play while this recording is paused.
        abandonFocus()
    }

    override fun resume() {
        val mediaPlayer = player ?: throw PlaybackUnavailableException("No recording is loaded")
        if (!requestFocus()) throw AudioFocusHeldException()
        try {
            applySpeed(mediaPlayer)
            mediaPlayer.start()
        } catch (exception: IllegalStateException) {
            abandonFocus()
            throw PlaybackUnavailableException("Could not resume recording", exception)
        }
        awaitingFocusGain = false
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        val mediaPlayer = player ?: return
        val wasPlaying = isPlaying(mediaPlayer)
        applySpeed(mediaPlayer)
        // Some devices start a paused player when its speed changes. A paused memory must stay paused.
        if (!wasPlaying && isPlaying(mediaPlayer)) {
            try {
                mediaPlayer.pause()
            } catch (exception: IllegalStateException) {
                // The player cannot pause in its current state.
            }
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

    override fun setInterruptionHandler(handler: (PlaybackInterruption) -> Unit) {
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
            // Reuse one request, so later requests keep the registration that reports the focus returning.
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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
        awaitingFocusGain = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun onFocusChange(change: Int) {
        val mediaPlayer = player ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (awaitingFocusGain) {
                awaitingFocusGain = false
                interruptionHandler(PlaybackInterruption.FOCUS_REGAINED)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Speech should not duck. The request stays registered, so the return of focus is reported.
                if (!isPlaying(mediaPlayer)) return
                pausePlayer(mediaPlayer)
                awaitingFocusGain = true
                interruptionHandler(PlaybackInterruption.TRANSIENT)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                pausePlayer(mediaPlayer)
                abandonFocus()
                interruptionHandler(PlaybackInterruption.PERMANENT)
            }
        }
    }

    private fun pausePlayer(mediaPlayer: MediaPlayer) {
        if (finished) return
        try {
            mediaPlayer.pause()
        } catch (exception: IllegalStateException) {
            // The player is not in a state that can pause, so there is nothing left to pause.
        }
    }

    private fun applySpeed(mediaPlayer: MediaPlayer) {
        try {
            // The pitch stays natural while the speed changes.
            mediaPlayer.playbackParams = PlaybackParams().setSpeed(speed).setPitch(NATURAL_PITCH)
        } catch (exception: IllegalStateException) {
            // The player cannot take new parameters in its current state. The next play or resume sets them.
        } catch (exception: IllegalArgumentException) {
            // The device rejects this speed, so the player keeps the speed it had.
        }
    }

    private fun isPlaying(mediaPlayer: MediaPlayer): Boolean =
        try {
            mediaPlayer.isPlaying
        } catch (exception: IllegalStateException) {
            false
        }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(noisyReceiver, filter)
        }
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        noisyReceiverRegistered = false
        appContext.unregisterReceiver(noisyReceiver)
    }

    private companion object {
        const val NORMAL_SPEED = 1f
        const val NATURAL_PITCH = 1f
    }
}
