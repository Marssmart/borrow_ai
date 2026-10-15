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
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            mediaRecorder.setAudioSamplingRate(AUDIO_SAMPLING_RATE)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
        } catch (exception: Exception) {
            mediaRecorder.release()
            throw RecordingUnavailableException("Could not start microphone capture", exception)
        }
        recorder = mediaRecorder
    }

    override fun stop() {
        val mediaRecorder = recorder ?: return
        recorder = null
        try {
            mediaRecorder.stop()
        } catch (exception: RuntimeException) {
            // MediaRecorder.stop() throws when no audio was captured. The file is unusable, so report it.
            throw RecordingUnavailableException("Could not finalize recording", exception)
        } finally {
            mediaRecorder.release()
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
