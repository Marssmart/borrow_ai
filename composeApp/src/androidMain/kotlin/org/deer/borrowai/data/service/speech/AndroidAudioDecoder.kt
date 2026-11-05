package org.deer.borrowai.data.service.speech

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.deer.borrowai.data.service.recording.LinearResampler

/** Shared M4A decoder for transcription and speaker analysis. */
internal class AndroidAudioDecoder(
    windowDurationSeconds: Int = DEFAULT_WINDOW_DURATION_SECONDS,
) {
    private val windowSamples = SAMPLE_RATE * windowDurationSeconds.also {
        require(it > 0) { "Audio window duration must be positive." }
    }

    suspend fun decodeToWindows(path: String, onWindow: (FloatArray) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val window = PcmWindowBuffer(windowSamples)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var resampler = LinearResampler(sourceRate, SAMPLE_RATE) { sample ->
                window.append(sample, onWindow)
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val stallGuard = DecodeStallGuard(STALL_TIMEOUT_MS)
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                stallGuard.check()
                var progressed = false
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        val format = decoder.outputFormat
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (newRate != sourceRate) {
                            sourceRate = newRate
                            resampler = LinearResampler(sourceRate, SAMPLE_RATE) { sample ->
                                window.append(sample, onWindow)
                            }
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val output = decoder.getOutputBuffer(outputIndex)!!
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            for (frame in 0 until frames) {
                                var sum = 0f
                                for (channel in 0 until channels) {
                                    sum += shorts.get() / PCM16_SCALE
                                }
                                resampler.push(sum / channels)
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        progressed = true
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                if (progressed) stallGuard.progress()
            }
            window.flush(onWindow)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val DEFAULT_WINDOW_DURATION_SECONDS = 30
        const val TIMEOUT_US = 10_000L
        const val STALL_TIMEOUT_MS = 30_000L
        const val PCM16_SCALE = 32768f
    }
}

private class PcmWindowBuffer(windowSamples: Int) {
    private val buffer = FloatArray(windowSamples)
    private var filled = 0

    fun append(sample: Float, onWindow: (FloatArray) -> Unit) {
        buffer[filled++] = sample
        if (filled == buffer.size) flush(onWindow)
    }

    fun flush(onWindow: (FloatArray) -> Unit) {
        if (filled == 0) return
        onWindow(buffer.copyOf(filled))
        filled = 0
    }

}
