package org.deer.borrowai.data.service.speaker

import okio.Path

/**
 * Finds who spoke when in a recording. A platform implementation runs segmentation and an embedding model on the device
 * and returns each speech turn with the embedding of its voice. Clustering and matching run in common code.
 */
interface SpeakerAnalyzer {
    /** False when the models or runtime are missing. Callers must not count this as a failed attempt. */
    suspend fun isAvailable(): Boolean

    /** The speech turns of the recording at [recordingPath], in time order. Empty when there is no speech. */
    suspend fun analyze(recordingPath: Path): List<SpeechTurn>
}

/** One stretch of speech and the embedding of its voice. Times are milliseconds from the start of the recording. */
class SpeechTurn(val startMs: Long, val endMs: Long, val embedding: FloatArray)

/**
 * The analyzer used on every platform until an embedding model is pinned (spike S-5 is open). It never analyzes, so no
 * memory gets speaker data.
 */
object UnavailableSpeakerAnalyzer : SpeakerAnalyzer {
    override suspend fun isAvailable(): Boolean = false

    override suspend fun analyze(recordingPath: Path): List<SpeechTurn> =
        error("Speaker analysis is unavailable on this device.")
}
