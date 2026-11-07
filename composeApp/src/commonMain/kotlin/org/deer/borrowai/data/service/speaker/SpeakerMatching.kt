package org.deer.borrowai.data.service.speaker

import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence

/** Cosine similarity at or above which an occurrence links to a library speaker (section 9.6). */
internal const val MATCH_THRESHOLD = 0.65

/** A library speaker and the best similarity between their voice and an occurrence. */
internal class SpeakerScore(val speakerId: String, val score: Double)

/** The link fields of an occurrence. */
internal data class OccurrenceLink(val speakerId: String?, val link: SpeakerLink)

/** A safe automatic link to apply to an occurrence that is still unassigned. */
internal data class UnassignedOccurrenceMatch(val occurrenceId: String, val speakerId: String)

/** Unit-length vectors of each attached speaker's embeddings, keeping only those stored with [modelVersion]. */
internal fun speakerVectors(embeddings: List<SpeakerEmbedding>, modelVersion: Int): Map<String, List<FloatArray>> {
    val vectors = mutableMapOf<String, MutableList<FloatArray>>()
    for (embedding in embeddings) {
        val speakerId = embedding.speakerId ?: continue
        if (embedding.modelVersion != modelVersion) continue
        vectors.getOrPut(speakerId) { mutableListOf() }.add(normalized(embedding.vector))
    }
    return vectors
}

/**
 * The best similarity of [centroid] to any vector of each speaker, leaving out the [rejected] speakers. [centroid] must
 * be unit length, as [meanDirection] returns.
 */
internal fun bestScores(
    centroid: FloatArray,
    vectors: Map<String, List<FloatArray>>,
    rejected: Set<String>,
): Map<String, Double> = vectors
    .filterKeys { it !in rejected }
    .mapValues { (_, own) -> own.maxOf { dot(centroid, it) } }

/**
 * The library speaker with the highest score, when that score reaches [MATCH_THRESHOLD]. Ties keep the speaker that
 * comes first in [scores].
 */
internal fun bestMatch(scores: Map<String, Double>): SpeakerScore? {
    val best = scores.maxByOrNull { it.value } ?: return null
    return if (best.value >= MATCH_THRESHOLD) SpeakerScore(best.key, best.value) else null
}

/** The link of an occurrence after matching. A [SpeakerLink.MANUAL] link is kept as it is (AC-4.3). */
internal fun resolveLink(current: OccurrenceLink, match: SpeakerScore?): OccurrenceLink = when {
    current.link == SpeakerLink.MANUAL -> current
    match != null -> OccurrenceLink(match.speakerId, SpeakerLink.AUTO)
    else -> OccurrenceLink(null, SpeakerLink.NONE)
}

/**
 * Plans automatic links for saved unknown occurrences, preserving one-speaker-per-memory assignment and each
 * occurrence's rejected-speaker list. Strongest matches win when several occurrences compete for one library speaker.
 */
internal fun planUnassignedOccurrenceMatches(
    occurrences: List<SpeakerOccurrence>,
    unknownEmbeddings: List<SpeakerEmbedding>,
    library: List<SpeakerEmbedding>,
    modelVersion: Int,
): List<UnassignedOccurrenceMatch> {
    val vectors = speakerVectors(library, modelVersion)
    if (vectors.isEmpty()) return emptyList()
    val unknownByOccurrence = unknownEmbeddings
        .filter { it.speakerId == null && it.modelVersion == modelVersion }
        .associateBy { it.sourceOccurrenceId }
    return occurrences.groupBy { it.memoryId }.values.flatMap { memoryOccurrences ->
        val assignedSpeakers = memoryOccurrences.mapNotNullTo(mutableSetOf()) { it.speakerId }
        val candidates = memoryOccurrences
            .filter { it.speakerId == null && it.link == SpeakerLink.NONE }
            .flatMap { occurrence ->
                val embedding = unknownByOccurrence[occurrence.id] ?: return@flatMap emptyList()
                bestScores(
                    centroid = normalized(embedding.vector),
                    vectors = vectors,
                    rejected = occurrence.rejectedSpeakerIds.toSet(),
                )
                    .filterValues { it >= MATCH_THRESHOLD }
                    .map { (speakerId, score) ->
                        CandidateMatch(occurrence.id, occurrence.ordinal, speakerId, score)
                    }
            }
            .sortedWith(
                compareByDescending<CandidateMatch> { it.score }
                    .thenBy { it.ordinal }
                    .thenBy { it.speakerId },
            )
        val claimedOccurrences = mutableSetOf<String>()
        val claimedSpeakers = assignedSpeakers
        candidates.mapNotNull { candidate ->
            if (candidate.occurrenceId in claimedOccurrences || candidate.speakerId in claimedSpeakers) {
                null
            } else {
                claimedOccurrences += candidate.occurrenceId
                claimedSpeakers += candidate.speakerId
                UnassignedOccurrenceMatch(candidate.occurrenceId, candidate.speakerId)
            }
        }
    }
}

private data class CandidateMatch(
    val occurrenceId: String,
    val ordinal: Int,
    val speakerId: String,
    val score: Double,
)
