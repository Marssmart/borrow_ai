package org.deer.borrowai.data.service.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records microphone audio as AAC in an M4A container using [MediaRecorder]. */
class AndroidAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    override fun start(absolutePath: String) {
        check(recorder == null) { "Recording already in progress" }
        val file = File(absolutePath)
        file.parentFile?.mkdirs()
        val mediaRecorder = createMediaRecorder()
        try {
            // Start the foreground service before capture so it keeps running after the app is minimized.
            MicrophoneForegroundService.start(context)
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            mediaRecorder.setAudioSamplingRate(AUDIO_SAMPLING_RATE)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (exception: Exception) {
            val unavailable = RecordingUnavailableException("Could not start microphone capture", exception)
            runCatching { mediaRecorder.release() }.exceptionOrNull()?.let(unavailable::addSuppressed)
            stopForegroundService(unavailable)
            throw unavailable
        }
        recorder = mediaRecorder
    }

    override fun stop() {
        val mediaRecorder = recorder ?: return
        recorder = null
        var failure: RecordingUnavailableException? = null
        try {
            mediaRecorder.stop()
        } catch (exception: RuntimeException) {
            // MediaRecorder.stop() throws when no audio was captured. The file is unusable, so report it.
            failure = RecordingUnavailableException("Could not finalize recording", exception)
        } finally {
            runCatching { mediaRecorder.release() }.exceptionOrNull()?.let { releaseFailure ->
                val current = failure
                if (current == null) {
                    failure = RecordingUnavailableException("Could not release recorder", releaseFailure)
                } else {
                    current.addSuppressed(releaseFailure)
                }
            }
            stopForegroundService(failure)
        }
        failure?.let { throw it }
    }

    /** A failure to stop the service must not replace the recorder's own result, so it is attached to that failure. */
    private fun stopForegroundService(primary: Throwable?) {
        runCatching { MicrophoneForegroundService.stop(context) }.exceptionOrNull()?.let { secondary ->
            primary?.addSuppressed(secondary)
        }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private companion object {
        const val AUDIO_BIT_RATE = 96_000
        const val AUDIO_SAMPLING_RATE = 44_100
    }
}
