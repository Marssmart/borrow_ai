package org.deer.borrowai.data.service.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.ModelSpec
import org.deer.borrowai.data.service.recording.BatchTranscriber

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

    override suspend fun isAvailable(): Boolean = resolve() != null

    override suspend fun transcribe(recordingPath: Path): String? = resolve()?.transcribe(recordingPath)

    private suspend fun resolve(): BatchTranscriber? {
        // Model verification hashes whole files, so it must not run on the caller's (possibly Main) dispatcher.
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                delegate ?: createBatchTranscriberIfAvailable(modelDirectory, totalRamBytes)?.also { delegate = it }
            }
        }
    }
}

/**
 * Decodes a finished M4A recording to 16 kHz mono and transcribes it in 30-second windows with the batch Whisper
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
            } catch (failure: TranscriptionStalledException) {
                Log.w(LOG_TAG, "Batch transcription stalled; the attempt is returned as failed", failure)
                null
            } catch (_: Throwable) {
                // Batch transcription is best-effort; the saved recording stays without a transcript.
                null
            } finally {
                runCatching { recognizer?.release() }
            }
        }
    }

    private suspend fun decodeToWindows(path: String, onWindow: (FloatArray) -> Unit) {
        AndroidAudioDecoder().decodeToWindows(path, onWindow)
    }

    private fun createRecognizer(): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OfflineModelConfig(
                whisper = whisperModelConfig(encoder = encoderPath, decoder = decoderPath),
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
    }
}

private const val LOG_TAG = "AndroidBatchTranscriber"

/** Raised when the audio decoder makes no input or output progress for the stall timeout, instead of hanging. */
internal class TranscriptionStalledException(timeoutMillis: Long) :
    IllegalStateException("Audio decode made no progress for $timeoutMillis ms")

/**
 * Fails a decode whose codec stops consuming input and producing output. Progress is measured on a monotonic clock, so
 * a healthy decode that keeps moving never trips it.
 */
internal class DecodeStallGuard(
    private val timeoutMillis: Long,
    private val nanoTime: () -> Long = { System.nanoTime() },
) {
    private val timeoutNanos = timeoutMillis * NANOS_PER_MILLI
    private var lastProgressNanos = nanoTime()

    fun progress() {
        lastProgressNanos = nanoTime()
    }

    fun check() {
        if (nanoTime() - lastProgressNanos > timeoutNanos) throw TranscriptionStalledException(timeoutMillis)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
