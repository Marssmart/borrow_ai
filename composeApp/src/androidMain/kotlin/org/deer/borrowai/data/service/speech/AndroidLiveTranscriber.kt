package org.deer.borrowai.data.service.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import okio.IOException
import okio.Path
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.ModelSpec
import org.deer.borrowai.data.service.model.modelDirectoryUnder
import org.deer.borrowai.data.service.recording.LiveAudioWindow
import org.deer.borrowai.data.service.recording.LiveTranscriber
import org.deer.borrowai.data.service.recording.LiveTranscriptionFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates a [LiveTranscriber] that resolves the Whisper model files each time a recording starts, on its background
 * thread. Models downloaded after app launch are picked up without a restart, and recording never waits on setup.
 * When the native runtime or any live model file is unavailable, the transcriber reports
 * [LiveTranscriptionFailure.MODEL_UNAVAILABLE] and recording continues.
 */
internal fun createLiveTranscriber(modelDirectory: Path, totalRamBytes: Long): LiveTranscriber =
    AndroidLiveTranscriber { resolveLiveModelPaths(modelDirectory, totalRamBytes) }

internal data class LiveModelPaths(val encoder: String, val decoder: String, val tokens: String)

private fun resolveLiveModelPaths(modelDirectory: Path, totalRamBytes: Long): LiveModelPaths? {
    if (!SherpaOnnxRuntime.isAvailable) return null
    val specs = ModelCatalog.modelsForRam(totalRamBytes).live
    val store = ModelFileStore(modelDirectory)
    val encoder = specs.firstOrNull { it.fileName.endsWith("-encoder.int8.onnx") } ?: return null
    val decoder = specs.firstOrNull { it.fileName.endsWith("-decoder.int8.onnx") } ?: return null
    val tokens = specs.firstOrNull { it.fileName.endsWith("-tokens.txt") } ?: return null
    val present = try {
        listOf(encoder, decoder, tokens).all { spec: ModelSpec -> store.isPresent(spec) }
    } catch (error: IOException) {
        // Presence checks hash files and can fail on storage errors; live text is optional, so skip it.
        Log.w(LOG_TAG, "Could not verify live transcription models; recording without a live transcript", error)
        return null
    }
    if (!present) return null
    return LiveModelPaths(
        encoder = store.finalPath(encoder).toString(),
        decoder = store.finalPath(decoder).toString(),
        tokens = store.finalPath(tokens).toString(),
    )
}

/**
 * Streams microphone audio into a rolling window and re-decodes it about once per second with a Whisper tiny
 * recognizer. Whisper is not truly streaming, so partial text is the decode of the latest window, emitted only
 * when it changes. Capture and decoding run on separate threads so slow inference cannot drop audio. Any failure is
 * reported once through onFailure and ends live transcription; the saved recording is unaffected.
 */
internal class AndroidLiveTranscriber(
    private val resolveModelPaths: () -> LiveModelPaths?,
) : LiveTranscriber {
    private val lock = Any()
    private var session: Session? = null

    // Kept across stop() so a restart still waits for an unfinished previous teardown.
    private var lastFinished: CountDownLatch? = null

    override fun start(onPartial: (String) -> Unit, onFailure: (LiveTranscriptionFailure) -> Unit) {
        val next: Session
        synchronized(lock) {
            // The new session waits for the previous capture to release the microphone before opening it.
            next = Session(lastFinished, onFailure)
            session?.stopLocked()
            session = next
            lastFinished = next.finished
        }
        val thread = Thread({ next.run(onPartial) }, "live-transcriber")
        thread.isDaemon = true
        thread.start()
    }

    override fun stop() {
        synchronized(lock) {
            session?.stopLocked()
            session = null
        }
    }

    private inner class Session(
        private val previousFinished: CountDownLatch?,
        private val onFailure: (LiveTranscriptionFailure) -> Unit,
    ) {
        /** Counted down once this session has released the microphone and recognizer. */
        val finished = CountDownLatch(1)

        // Guarded by [lock]. Lets stop() halt capture even while the worker is still setting up.
        private var active: Boolean = true
        private var failureReported: Boolean = false
        private var record: AudioRecord? = null

        /** Must be called with [lock] held. */
        fun stopLocked() {
            active = false
            runCatching { record?.stop() }
        }

        private fun isActive(): Boolean = synchronized(lock) { active }

        /**
         * Must be called with [lock] held. Reports at most once per session, and only while the session is still
         * active, so failures caused by stop() are not surfaced as errors.
         */
        private fun reportLocked(failure: LiveTranscriptionFailure) {
            if (!active || failureReported) return
            failureReported = true
            onFailure(failure)
        }

        private fun report(failure: LiveTranscriptionFailure) = synchronized(lock) { reportLocked(failure) }

        fun run(onPartial: (String) -> Unit) {
            var recognizer: OfflineRecognizer? = null
            var decoder: ExecutorService? = null
            try {
                previousFinished?.await()
                if (!isActive()) return
                val paths = try {
                    resolveModelPaths()
                } catch (t: Throwable) {
                    Log.w(LOG_TAG, "Could not resolve live transcription models", t)
                    null
                }
                if (paths == null) {
                    report(LiveTranscriptionFailure.MODEL_UNAVAILABLE)
                    return
                }
                recognizer = try {
                    createRecognizer(paths)
                } catch (t: Throwable) {
                    Log.w(LOG_TAG, "Live transcription engine failed to initialize", t)
                    report(LiveTranscriptionFailure.ENGINE_FAILED)
                    return
                }
                if (!isActive()) return
                val audio = try {
                    createAudioRecord()
                } catch (t: Throwable) {
                    Log.w(LOG_TAG, "Could not create live audio input", t)
                    null
                }
                if (audio == null) {
                    report(LiveTranscriptionFailure.MICROPHONE_UNAVAILABLE)
                    return
                }
                synchronized(lock) {
                    if (!active) {
                        audio.release()
                        return
                    }
                    record = audio
                }
                try {
                    audio.startRecording()
                } catch (t: Throwable) {
                    Log.w(LOG_TAG, "Could not start live audio input", t)
                    report(LiveTranscriptionFailure.MICROPHONE_UNAVAILABLE)
                    return
                }
                decoder = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "live-transcriber-decode").apply { isDaemon = true }
                }
                capture(audio, recognizer, decoder, onPartial)
            } catch (t: Throwable) {
                // Live text is best-effort; the recording keeps running and the user is told why text stopped.
                Log.w(LOG_TAG, "Live transcription stopped unexpectedly; recording continues", t)
                report(LiveTranscriptionFailure.TRANSCRIPTION_FAILED)
            } finally {
                // Capture runs on this thread, so the read loop has already exited and the microphone can be released.
                val audio = synchronized(lock) {
                    stopLocked()
                    record.also { record = null }
                }
                runCatching { audio?.release() }
                val pendingDecoder = decoder
                val pendingRecognizer = recognizer
                if (pendingDecoder == null) {
                    runCatching { pendingRecognizer?.release() }
                    finished.countDown()
                } else {
                    // Native inference may still be using the recognizer. Wait off this thread for the decoder to stop
                    // before releasing it, and only then let the next session start.
                    pendingDecoder.shutdownNow()
                    Thread({
                        runCatching { pendingDecoder.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS) }
                        runCatching { pendingRecognizer?.release() }
                        finished.countDown()
                    }, "live-transcriber-release").apply { isDaemon = true }.start()
                }
            }
        }

        private fun capture(
            audio: AudioRecord,
            recognizer: OfflineRecognizer,
            decoder: ExecutorService,
            onPartial: (String) -> Unit,
        ) {
            val window = LiveAudioWindow(WINDOW_SAMPLES)
            val windowLock = Any()
            val chunk = ShortArray(CHUNK_SAMPLES)
            val decodeQueued = AtomicBoolean(false)
            var samplesSinceDecode = 0
            var lastText = ""
            while (isActive()) {
                val read = audio.read(chunk, 0, chunk.size)
                if (read < 0) {
                    // Only reached while the session is active; a stop() that ends the read is not an interruption.
                    synchronized(lock) { reportLocked(LiveTranscriptionFailure.AUDIO_INTERRUPTED) }
                    break
                }
                if (read == 0) continue
                synchronized(windowLock) { window.appendPcm16(chunk, read) }
                samplesSinceDecode += read
                if (samplesSinceDecode < DECODE_INTERVAL_SAMPLES) continue
                if (window.size < MIN_DECODE_SAMPLES) continue
                samplesSinceDecode = 0
                // Capture never waits for inference. If a decode is still running, the newest audio is decoded next.
                if (!decodeQueued.compareAndSet(false, true)) continue
                decoder.execute {
                    try {
                        val samples = synchronized(windowLock) { window.snapshot() }
                        val text = decode(recognizer, samples)
                        if (text.isNotBlank() && text != lastText) {
                            lastText = text
                            // Checked under the lock so stop() guarantees no callback after it returns.
                            synchronized(lock) {
                                if (active && session === this@Session) onPartial(text)
                            }
                        }
                    } catch (t: Throwable) {
                        // Runs on the executor, outside Session.run()'s try/catch. Report, then stop so capture cleanup runs.
                        Log.w(LOG_TAG, "Live decode failed; stopping live transcription", t)
                        synchronized(lock) {
                            reportLocked(LiveTranscriptionFailure.TRANSCRIPTION_FAILED)
                            stopLocked()
                        }
                    } finally {
                        decodeQueued.set(false)
                    }
                }
            }
        }

        private fun createRecognizer(paths: LiveModelPaths): OfflineRecognizer {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    whisper = whisperModelConfig(encoder = paths.encoder, decoder = paths.decoder),
                    tokens = paths.tokens,
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

private const val LOG_TAG = "AndroidLiveTranscriber"
