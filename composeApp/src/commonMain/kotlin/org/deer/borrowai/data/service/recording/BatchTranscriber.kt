package org.deer.borrowai.data.service.recording

import okio.Path

/**
 * Produces a full transcript of a finished recording file. Runs after the memory is saved, so a failure only leaves
 * the memory without a transcript. Implementations return null when transcription cannot run, and rethrow only
 * cancellation.
 */
interface BatchTranscriber {
    /** False when the models or runtime are missing. Callers must not count such a state as a failed attempt. */
    suspend fun isAvailable(): Boolean = true

    suspend fun transcribe(recordingPath: Path): String?
}
