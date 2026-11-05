package org.deer.borrowai.data.service.speech

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
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
import org.deer.borrowai.data.service.model.ModelRole
import org.deer.borrowai.data.service.speaker.SpeakerAnalyzer
import org.deer.borrowai.data.service.speaker.SpeakerAnalysisPausedException
import org.deer.borrowai.data.service.speaker.SpeechTurn

internal class AndroidSpeakerAnalyzer(
    modelDirectory: Path,
) : SpeakerAnalyzer {
    private val store = ModelFileStore(modelDirectory)
    private val gate = Mutex()
    private val segmentation = ModelCatalog.speakerModels.single {
        it.role == ModelRole.SPEAKER_SEGMENTATION
    }
    private val embedding = ModelCatalog.speakerModels.single {
        it.role == ModelRole.SPEAKER_EMBEDDING
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!SherpaOnnxRuntime.isAvailable) return@withContext false
        runCatching { store.isPresent(segmentation) && store.isPresent(embedding) }.getOrDefault(false)
    }

    override suspend fun analyze(
        recordingPath: Path,
        shouldPause: () -> Boolean,
    ): List<SpeechTurn> = gate.withLock {
        withContext(Dispatchers.Default) {
            check(isAvailable()) { "Speaker models are unavailable or failed integrity verification." }
            if (shouldPause()) throw SpeakerAnalysisPausedException()

            var diarizer: OfflineSpeakerDiarization? = null
            var extractor: SpeakerEmbeddingExtractor? = null
            try {
                val createdDiarizer = OfflineSpeakerDiarization(
                    config = OfflineSpeakerDiarizationConfig(
                        segmentation = OfflineSpeakerSegmentationModelConfig(
                            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                                model = store.finalPath(segmentation).toString(),
                            ),
                            numThreads = THREADS,
                            debug = false,
                            provider = "cpu",
                        ),
                        embedding = SpeakerEmbeddingExtractorConfig(
                            model = store.finalPath(embedding).toString(),
                            numThreads = THREADS,
                            debug = false,
                            provider = "cpu",
                        ),
                        clustering = FastClusteringConfig(
                            numClusters = -1,
                            threshold = CLUSTERING_THRESHOLD,
                            computeConfidence = false,
                        ),
                        minDurationOn = MIN_DURATION_ON_SECONDS,
                        minDurationOff = MIN_DURATION_OFF_SECONDS,
                    ),
                )
                diarizer = createdDiarizer
                val createdExtractor = SpeakerEmbeddingExtractor(
                    config = SpeakerEmbeddingExtractorConfig(
                        model = store.finalPath(embedding).toString(),
                        numThreads = THREADS,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                extractor = createdExtractor
                val coroutineContext = currentCoroutineContext()
                val turns = mutableListOf<SpeechTurn>()
                var sampleOffset = 0L
                AndroidAudioDecoder(windowDurationSeconds = ANALYSIS_WINDOW_SECONDS)
                    .decodeToWindows(recordingPath.toString()) { pcm ->
                        coroutineContext.ensureActive()
                        if (shouldPause()) throw SpeakerAnalysisPausedException()
                        val segments = createdDiarizer.process(pcm)
                        if (shouldPause()) throw SpeakerAnalysisPausedException()
                        segments.forEach { segment ->
                            coroutineContext.ensureActive()
                            if (shouldPause()) throw SpeakerAnalysisPausedException()
                            val start = (segment.start * SAMPLE_RATE).toInt().coerceIn(0, pcm.size)
                            val end = (segment.end * SAMPLE_RATE).toInt().coerceIn(0, pcm.size)
                            if (end - start >= MIN_EMBEDDING_SAMPLES) {
                                val stream = createdExtractor.createStream()
                                try {
                                    stream.acceptWaveform(pcm.copyOfRange(start, end), SAMPLE_RATE)
                                    stream.inputFinished()
                                    if (createdExtractor.isReady(stream)) {
                                        val vector = createdExtractor.compute(stream)
                                        check(vector.size == createdExtractor.dim() && vector.isNotEmpty()) {
                                            "Speaker embedding output did not match the model dimension."
                                        }
                                        turns += SpeechTurn(
                                            startMs = sampleOffset * MILLIS_PER_SECOND / SAMPLE_RATE +
                                                (segment.start * MILLIS_PER_SECOND).toLong(),
                                            endMs = sampleOffset * MILLIS_PER_SECOND / SAMPLE_RATE +
                                                (segment.end * MILLIS_PER_SECOND).toLong(),
                                            embedding = vector,
                                        )
                                    }
                                } finally {
                                    stream.release()
                                }
                            }
                        }
                        sampleOffset += pcm.size
                    }
                if (shouldPause()) throw SpeakerAnalysisPausedException()
                turns
            } catch (failure: CancellationException) {
                throw failure
            } finally {
                runCatching { extractor?.release() }
                runCatching { diarizer?.release() }
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MILLIS_PER_SECOND = 1_000L
        const val THREADS = 2
        const val CLUSTERING_THRESHOLD = 0.5f
        const val MIN_DURATION_ON_SECONDS = 0.2f
        const val MIN_DURATION_OFF_SECONDS = 0.5f
        const val ANALYSIS_WINDOW_SECONDS = 10
        const val MIN_EMBEDDING_SAMPLES = SAMPLE_RATE * 3 / 2
    }
}
