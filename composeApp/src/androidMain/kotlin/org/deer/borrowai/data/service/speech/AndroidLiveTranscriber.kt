package org.deer.borrowai.data.service.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import okio.Path
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.modelDirectoryUnder
import org.deer.borrowai.data.service.recording.LiveAudioWindow
import org.deer.borrowai.data.service.recording.LiveTranscriber

/**
 * Creates a [LiveTranscriber] when the native runtime is present. Model files are checked each time a recording
 * starts, so a model downloaded after launch is picked up without restarting the app.
 */
internal fun createLiveTranscriberIfAvailable(modelDirectory: Path, totalRamBytes: Long): LiveTranscriber? {
    if (!SherpaOnnxRuntime.isAvailable) return null
    return AndroidLiveTranscriber(models = { resolveLiveModels(modelDirectory, totalRamBytes) })
}

internal class LiveModelPaths(val encoderPath: String, val decoderPath: String, val tokensPath: String)

/** Returns the Whisper tiny int8 paths, or null when any live model file is missing or fails verification. */
private fun resolveLiveModels(modelDirectory: Path, totalRamBytes: Long): LiveModelPaths? {
    val specs = ModelCatalog.modelsForRam(totalRamBytes).live
    val encoder = specs.firstOrNull { it.fileName.endsWith("-encoder.int8.onnx") } ?: return null
    val decoder = specs.firstOrNull { it.fileName.endsWith("-decoder.int8.onnx") } ?: return null
    val tokens = specs.firstOrNull { it.fileName.endsWith("-tokens.txt") } ?: return null
    val store = ModelFileStore(modelDirectory)
    if (!listOf(encoder, decoder, tokens).all(store::isPresent)) return null
    return LiveModelPaths(
        encoderPath = store.finalPath(encoder).toString(),
        decoderPath = store.finalPath(decoder).toString(),
        tokensPath = store.finalPath(tokens).toString(),
    )
}

/**
 * Streams microphone audio into a rolling window and re-decodes it about once per second with a Whisper tiny
 * recognizer. Whisper is not truly streaming, so partial text is the decode of the latest window, emitted only
 * when it changes. Failures end live transcription silently; the saved recording is unaffected.
 */
internal class AndroidLiveTranscriber(
    private val models: () -> LiveModelPaths?,
) : LiveTranscriber {
    private val lock = Any()
    private var session: Session? = null

    override fun start(onPartial: (String) -> Unit): Boolean {
        val next = Session(onPartial)
        synchronized(lock) {
            session?.stop()
            session = next
        }
        next.start()
        return true
    }

    override fun stop() {
        synchronized(lock) {
            session?.stop()
            session = null
        }
    }

    private inner class Session(private val onPartial: (String) -> Unit) {
        private val window = LiveAudioWindow(WINDOW_SAMPLES)
        private val windowLock = Any()

        @Volatile
        private var active = true

        @Volatile
        private var record: AudioRecord? = null

        fun start() {
            Thread({ capture() }, "live-capture").apply { isDaemon = true }.start()
        }

        /** Halts capture immediately; the capture thread finishes cleanup on its own. */
        fun stop() {
            active = false
            record?.let { runCatching { it.stop() } }
        }

        private fun capture() {
            var recognizer: OfflineRecognizer? = null
            var audio: AudioRecord? = null
            var decoder: Thread? = null
            try {
                val paths = models() ?: return
                val ready = createRecognizer(paths).also { recognizer = it }
                val input = createAudioRecord() ?: return
                audio = input
                record = input
                if (!active) return
                input.startRecording()
                decoder = Thread({ decodeLoop(ready) }, "live-decode").apply { isDaemon = true; start() }
                readLoop(input)
            } catch (t: Throwable) {
                // Live text is best-effort; ending here leaves the recording running without a transcript.
                Log.w(TAG, "Live transcription failed; recording continues without a live transcript", t)
            } finally {
                active = false
                audio?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                // The decoder must finish before the recognizer is released.
                runCatching { decoder?.join() }
                runCatching { recognizer?.release() }
            }
        }

        /** Drains the microphone as fast as it delivers, so slow decodes never overflow the capture buffer. */
        private fun readLoop(input: AudioRecord) {
            val chunk = ShortArray(CHUNK_SAMPLES)
            while (active) {
                val read = input.read(chunk, 0, chunk.size)
                if (read < 0) break
                if (read == 0) continue
                synchronized(windowLock) { window.appendPcm16(chunk, read) }
            }
        }

        /** Decodes the latest window once per interval, on its own thread beside [readLoop]. */
        private fun decodeLoop(recognizer: OfflineRecognizer) {
            var lastText = ""
            try {
                while (active) {
                    Thread.sleep(DECODE_INTERVAL_MS)
                    val samples = synchronized(windowLock) {
                        if (window.size < MIN_DECODE_SAMPLES) null else window.snapshot()
                    } ?: continue
                    val text = decode(recognizer, samples)
                    if (text.isBlank() || text == lastText) continue
                    lastText = text
                    // Checked under the lock so stop() guarantees no callback after it returns.
                    synchronized(lock) {
                        if (active && session === this) onPartial(text)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Live decoding stopped; recording continues without a live transcript", t)
                active = false
            }
        }

        private fun createRecognizer(paths: LiveModelPaths): OfflineRecognizer {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = paths.encoderPath,
                        decoder = paths.decoderPath,
                        language = "",
                        task = "transcribe",
                    ),
                    tokens = paths.tokensPath,
                    numThreads = THREADS,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            // A null AssetManager makes the AAR load the model from the filesystem paths above. The AAR exposes no
            // dedicated path-based constructor.
            return OfflineRecognizer(null, config)
        }

        private fun createAudioRecord(): AudioRecord? {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return null
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK_SAMPLES * 2 * 2),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            return record
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
    }

    private companion object {
        const val TAG = "AndroidLiveTranscriber"
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val THREADS = 2
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10
        const val DECODE_INTERVAL_MS = 1_000L
        const val MIN_DECODE_SAMPLES = SAMPLE_RATE / 2
        const val WINDOW_SAMPLES = SAMPLE_RATE * 30
    }
}
