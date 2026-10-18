package org.deer.borrowai.data.service.recording

import okio.Path

/**
 * Produces a full transcript of a finished recording file. Runs after the memory is saved, so a failure only leaves
 * the memory without a transcript. Implementations return null when transcription cannot run, and rethrow only
 * cancellation.
 */
interface BatchTranscriber {
    suspend fun transcribe(recordingPath: Path): String?
}
