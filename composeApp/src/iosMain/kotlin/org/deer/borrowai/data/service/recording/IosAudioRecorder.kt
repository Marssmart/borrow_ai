package org.deer.borrowai.data.service.recording

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioQualityHigh
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

/** Records microphone audio as AAC in an M4A container using [AVAudioRecorder]. */
@OptIn(ExperimentalForeignApi::class)
class IosAudioRecorder : AudioRecorder {
    private var recorder: AVAudioRecorder? = null

    override fun start(absolutePath: String) {
        check(recorder == null) { "Recording already in progress" }
        val session = AVAudioSession.sharedInstance()
        val settings: Map<Any?, Any> = mapOf(
            AVFormatIDKey to NSNumber(int = kAudioFormatMPEG4AAC.toInt()),
            AVSampleRateKey to NSNumber(double = SAMPLE_RATE),
            AVNumberOfChannelsKey to NSNumber(int = CHANNEL_COUNT),
            AVEncoderAudioQualityKey to NSNumber(int = AVAudioQualityHigh.toInt()),
        )
        val created = try {
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
            AVAudioRecorder(uRL = NSURL.fileURLWithPath(absolutePath), settings = settings, error = null)
        } catch (exception: Exception) {
            throw RecordingUnavailableException("Could not configure audio session", exception)
        }
        if (!created.prepareToRecord() || !created.record()) {
            throw RecordingUnavailableException("Could not start microphone capture")
        }
        recorder = created
    }

    override fun stop() {
        val active = recorder ?: return
        recorder = null
        active.stop()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100.0
        const val CHANNEL_COUNT = 1
    }
}
