package org.deer.borrowai.data.domain

/**
 * Where a memory stands in speaker analysis. It follows the same lifecycle as [TranscriptionStatus].
 * [SKIPPED] means the memory was not analyzed: the feature is off, the analyzer is unavailable, there is no
 * audio, or there is no speech.
 */
enum class SpeakerStatus { PENDING, ANALYZING, DONE, FAILED, SKIPPED }

/** How many attempts a memory gets before its speaker analysis is marked [SpeakerStatus.FAILED] (section 7.1). */
const val MAX_SPEAKER_ATTEMPTS = 3

/** Version of the speaker model and clustering that a completed analysis is written with (section 6.1). */
const val SPEAKER_ANALYSIS_VERSION = 1

/** Most embeddings a library speaker keeps. Adding one past the limit drops the oldest (section 6.2). */
const val MAX_EMBEDDINGS_PER_SPEAKER = 10

/** A person in the speaker library. [lastUsedAtEpochMillis] orders the assign sheet. */
data class Speaker(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val lastUsedAtEpochMillis: Long,
    val embeddingCount: Int,
) {
    init {
        require(id.isNotBlank()) { "A speaker id cannot be blank." }
        require(name.isNotBlank()) { "A speaker name cannot be blank." }
        require(embeddingCount in 0..MAX_EMBEDDINGS_PER_SPEAKER) {
            "A speaker keeps at most $MAX_EMBEDDINGS_PER_SPEAKER embeddings."
        }
    }
}

/** Whether an occurrence is linked to a library speaker. A [MANUAL] link is never overwritten by matching (AC-4.3). */
enum class SpeakerLink { NONE, AUTO, MANUAL }

/**
 * A voiceprint stored for one occurrence. [speakerId] is null while the occurrence is unknown. Vectors are compared
 * only with vectors of the same [modelVersion]. Equality compares vector contents. [toString] leaves the vector out,
 * so a voiceprint never reaches a log by accident.
 */
data class SpeakerEmbedding(
    val id: String,
    val memoryId: String,
    val speakerId: String?,
    val vector: FloatArray,
    val modelVersion: Int,
    val sourceOccurrenceId: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(vector.isNotEmpty()) { "An embedding cannot be empty." }
        require(modelVersion >= 1) { "Model versions start at 1." }
    }

    override fun equals(other: Any?): Boolean =
        other is SpeakerEmbedding &&
            id == other.id &&
            memoryId == other.memoryId &&
            speakerId == other.speakerId &&
            vector.contentEquals(other.vector) &&
            modelVersion == other.modelVersion &&
            sourceOccurrenceId == other.sourceOccurrenceId &&
            createdAtEpochMillis == other.createdAtEpochMillis

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + memoryId.hashCode()
        result = 31 * result + (speakerId?.hashCode() ?: 0)
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + modelVersion
        result = 31 * result + sourceOccurrenceId.hashCode()
        result = 31 * result + createdAtEpochMillis.hashCode()
        return result
    }

    override fun toString(): String =
        "SpeakerEmbedding(id=$id, memoryId=$memoryId, speakerId=$speakerId, modelVersion=$modelVersion, " +
            "sourceOccurrenceId=$sourceOccurrenceId, createdAtEpochMillis=$createdAtEpochMillis)"
}

/**
 * One speaker found in one memory: a cluster of speech with its sample range and its embedding. [ordinal] is the
 * 1-based "Speaker N" label. A [SpeakerLink.NONE] occurrence has no speaker, and every other occurrence has one.
 */
data class SpeakerOccurrence(
    val id: String,
    val memoryId: String,
    val ordinal: Int,
    val speakerId: String?,
    val link: SpeakerLink,
    val rejectedSpeakerIds: List<String>,
    val totalSpeechMs: Long,
    val sampleStartMs: Long,
    val sampleEndMs: Long,
    val embeddingId: String,
) {
    init {
        require(ordinal >= 1) { "Speaker ordinals start at 1." }
        require(totalSpeechMs >= 0) { "Speech time cannot be negative." }
        require(sampleStartMs >= 0 && sampleEndMs > sampleStartMs) { "A sample needs a positive time range." }
        require((link == SpeakerLink.NONE) == (speakerId == null)) {
            "An occurrence has a speaker exactly when it is linked."
        }
    }
}

/**
 * An occurrence found by analysis, before the store assigns its ids. [centroid] is the unit-length voiceprint of the
 * occurrence's speech. The link rules are the same as for [SpeakerOccurrence].
 */
class OccurrenceDraft(
    val ordinal: Int,
    val speakerId: String?,
    val link: SpeakerLink,
    val totalSpeechMs: Long,
    val sampleStartMs: Long,
    val sampleEndMs: Long,
    val centroid: FloatArray,
    val rejectedSpeakerIds: List<String> = emptyList(),
) {
    init {
        require(ordinal >= 1) { "Speaker ordinals start at 1." }
        require(totalSpeechMs >= 0) { "Speech time cannot be negative." }
        require(sampleStartMs >= 0 && sampleEndMs > sampleStartMs) { "A sample needs a positive time range." }
        require(centroid.isNotEmpty()) { "A centroid cannot be empty." }
        require((link == SpeakerLink.NONE) == (speakerId == null)) {
            "An occurrence has a speaker exactly when it is linked."
        }
    }
}
