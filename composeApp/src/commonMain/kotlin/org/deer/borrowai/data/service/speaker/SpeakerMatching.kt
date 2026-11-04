package org.deer.borrowai.data.service.speaker

import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink

/** Cosine similarity at or above which an occurrence links to a library speaker (section 9.6). */
internal const val MATCH_THRESHOLD = 0.65

/** A library speaker and the best similarity between their voice and an occurrence. */
internal class SpeakerScore(val speakerId: String, val score: Double)

/** The link fields of an occurrence. */
internal data class OccurrenceLink(val speakerId: String?, val link: SpeakerLink)

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
