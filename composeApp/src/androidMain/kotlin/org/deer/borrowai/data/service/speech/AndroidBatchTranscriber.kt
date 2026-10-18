package org.deer.borrowai.data.service.speech

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.ModelSpec
import org.deer.borrowai.data.service.recording.BatchTranscriber
import org.deer.borrowai.data.service.recording.LinearResampler

/**
 * Creates a [BatchTranscriber] backed by the tier's batch Whisper model, or returns null when the native runtime or
 * any of its model files is unavailable. Callers keep the saved recording without a transcript when this is null.
 */
internal fun createBatchTranscriberIfAvailable(modelDirectory: Path, totalRamBytes: Long): BatchTranscriber? {
    if (!SherpaOnnxRuntime.isAvailable) return null
    val specs = ModelCatalog.modelsForRam(totalRamBytes).voice
    val store = ModelFileStore(modelDirectory)
    val encoder = specs.firstOrNull { it.fileName.endsWith("-encoder.int8.onnx") } ?: return null
    val decoder = specs.firstOrNull { it.fileName.endsWith("-decoder.int8.onnx") } ?: return null
    val tokens = specs.firstOrNull { it.fileName.endsWith("-tokens.txt") } ?: return null
    // A storage error counts as "models unavailable"; the saved recording stays without a transcript.
    val present = runCatching { listOf(encoder, decoder, tokens).all { spec: ModelSpec -> store.isPresent(spec) } }
        .getOrDefault(false)
    if (!present) return null
    return AndroidBatchTranscriber(
        encoderPath = store.finalPath(encoder).toString(),
        decoderPath = store.finalPath(decoder).toString(),
        tokensPath = store.finalPath(tokens).toString(),
    )
}

/**
 * Resolves the batch model when it is first needed, so models downloaded after startup are picked up. Once resolved,
 * the same [AndroidBatchTranscriber] is reused so its transcription lock is shared.
 */
internal class LazyBatchTranscriber(
    private val modelDirectory: Path,
    private val totalRamBytes: Long,
) : BatchTranscriber {
    private val lock = Any()
    private var delegate: BatchTranscriber? = null

    override suspend fun transcribe(recordingPath: Path): String? {
        // Model verification hashes whole files, so it must not run on the caller's (possibly Main) dispatcher.
        val resolved = withContext(Dispatchers.IO) {
            synchronized(lock) {
                delegate ?: createBatchTranscriberIfAvailable(modelDirectory, totalRamBytes)?.also { delegate = it }
            }
        } ?: return null
        return resolved.transcribe(recordingPath)
    }
}

/**
 * Decodes a finished M4A recording to 16 kHz mono and transcribes it in 29-second windows with the batch Whisper
 * model. Whisper decodes one window at a time, so window boundaries can split words. Transcriptions run one at a time
 * so concurrent saves do not each load a native model.
 */
internal class AndroidBatchTranscriber(
    private val encoderPath: String,
    private val decoderPath: String,
    private val tokensPath: String,
) : BatchTranscriber {
    private val gate = Mutex()

    override suspend fun transcribe(recordingPath: Path): String? = gate.withLock {
        withContext(Dispatchers.Default) {
            var recognizer: OfflineRecognizer? = null
            try {
                val created = createRecognizer()
                recognizer = created
                val texts = mutableListOf<String>()
                decodeToWindows(recordingPath.toString()) { window ->
                    val text = decode(created, window)
                    if (text.isNotBlank()) texts += text
                }
                texts.joinToString(" ")
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                // Batch transcription is best-effort; the saved recording stays without a transcript.
                null
            } finally {
                runCatching { recognizer?.release() }
            }
        }
    }

    private suspend fun decodeToWindows(path: String, onWindow: (FloatArray) -> Unit) {
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

            val window = WindowBuffer()
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var resampler = LinearResampler(sourceRate, SAMPLE_RATE) { sample -> window.append(sample, onWindow) }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
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
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
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
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            window.flush(onWindow)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    language = "",
                    task = "transcribe",
                ),
                tokens = tokensPath,
                numThreads = THREADS,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        // A null AssetManager makes the AAR load the model from the filesystem paths above.
        return OfflineRecognizer(null, config)
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val THREADS = 2
        const val TIMEOUT_US = 10_000L
        const val PCM16_SCALE = 32768f
    }
}

/** Collects resampled samples into Whisper-sized windows, handing each full window to the caller. */
private class WindowBuffer {
    private val buffer = FloatArray(WINDOW_SAMPLES)
    private var filled = 0

    fun append(sample: Float, onWindow: (FloatArray) -> Unit) {
        buffer[filled++] = sample
        if (filled == WINDOW_SAMPLES) flush(onWindow)
    }

    fun flush(onWindow: (FloatArray) -> Unit) {
        if (filled == 0) return
        onWindow(buffer.copyOf(filled))
        filled = 0
    }

    private companion object {
        // Whisper clamps input to about 29.5 seconds, so 29-second windows avoid dropping audio at each boundary.
        const val WINDOW_SAMPLES = 16_000 * 29
    }
}
