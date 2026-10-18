package org.deer.borrowai.data.service.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import okio.Path
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.ModelSpec
import org.deer.borrowai.data.service.model.modelDirectoryUnder
import org.deer.borrowai.data.service.recording.LiveAudioWindow
import org.deer.borrowai.data.service.recording.LiveTranscriber

/**
 * Creates a [LiveTranscriber] backed by the Whisper tiny int8 model, or returns null when the native runtime or any
 * live model file is unavailable. Callers keep recording normally when this returns null.
 */
internal fun createLiveTranscriberIfAvailable(modelDirectory: Path, totalRamBytes: Long): LiveTranscriber? {
    if (!SherpaOnnxRuntime.isAvailable) return null
    val specs = ModelCatalog.modelsForRam(totalRamBytes).live
    val store = ModelFileStore(modelDirectory)
    val encoder = specs.firstOrNull { it.fileName.endsWith("-encoder.int8.onnx") } ?: return null
    val decoder = specs.firstOrNull { it.fileName.endsWith("-decoder.int8.onnx") } ?: return null
    val tokens = specs.firstOrNull { it.fileName.endsWith("-tokens.txt") } ?: return null
    val present = listOf(encoder, decoder, tokens).all { spec: ModelSpec -> store.isPresent(spec) }
    if (!present) return null
    return AndroidLiveTranscriber(
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
    private val encoderPath: String,
    private val decoderPath: String,
    private val tokensPath: String,
) : LiveTranscriber {
    private val lock = Any()
    private var session: Session? = null

    override fun start(onPartial: (String) -> Unit): Boolean {
        val next = Session()
        synchronized(lock) {
            session?.active = false
            session = next
        }
        val thread = Thread({ next.run(onPartial) }, "live-transcriber")
        thread.isDaemon = true
        thread.start()
        return true
    }

    override fun stop() {
        synchronized(lock) {
            session?.active = false
            session = null
        }
    }

    private inner class Session {
        @Volatile
        var active: Boolean = true

        fun run(onPartial: (String) -> Unit) {
            var recognizer: OfflineRecognizer? = null
            var record: AudioRecord? = null
            try {
                recognizer = createRecognizer()
                record = createAudioRecord() ?: return
                record.startRecording()
                val window = LiveAudioWindow(WINDOW_SAMPLES)
                val chunk = ShortArray(CHUNK_SAMPLES)
                var samplesSinceDecode = 0
                var lastText = ""
                while (active) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read < 0) break
                    if (read == 0) continue
                    window.appendPcm16(chunk, read)
                    samplesSinceDecode += read
                    if (samplesSinceDecode < DECODE_INTERVAL_SAMPLES || window.size < MIN_DECODE_SAMPLES) continue
                    samplesSinceDecode = 0
                    val text = decode(recognizer, window.snapshot())
                    if (active && text.isNotBlank() && text != lastText) {
                        lastText = text
                        onPartial(text)
                    }
                }
            } catch (_: Throwable) {
                // Live text is best-effort; ending here leaves the recording running without a transcript.
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                runCatching { recognizer?.release() }
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
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val THREADS = 2
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10
        const val DECODE_INTERVAL_SAMPLES = SAMPLE_RATE
        const val MIN_DECODE_SAMPLES = SAMPLE_RATE / 2
        const val WINDOW_SAMPLES = SAMPLE_RATE * 30
    }
}
