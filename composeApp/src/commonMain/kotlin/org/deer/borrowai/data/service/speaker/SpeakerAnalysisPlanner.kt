package org.deer.borrowai.data.service.speaker

import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink

/**
 * Turns the speech turns of one recording into occurrence drafts. Clusters the turns into speakers, picks a sample for
 * each one, and links each to its best library match. A speaker with no sample free of other speakers is dropped, so
 * every draft can be played back. Returns an empty list when there is no speech.
 */
internal fun planSpeakerOccurrences(
    turns: List<SpeechTurn>,
    library: List<SpeakerEmbedding>,
    modelVersion: Int,
): List<OccurrenceDraft> {
    validateTurns(turns)
    val labels = clusterTurns(turns)
    val speakerCount = (labels.maxOrNull() ?: -1) + 1
    val planned = mutableListOf<PlannedSpeaker>()
    for (label in 0 until speakerCount) {
        val own = turns.indices.filter { labels[it] == label }
        val ownRanges = own.map { turns[it].toRange() }
        val otherRanges = turns.indices.filter { labels[it] != label }.map { turns[it].toRange() }
        val sample = selectSample(ownRanges, otherRanges) ?: continue
        planned += PlannedSpeaker(
            sample = sample,
            speechMs = unionLength(ownRanges),
            centroid = meanDirection(own.map { normalized(turns[it].embedding) }),
        )
    }
    val vectors = speakerVectors(library, modelVersion)
    return planned.mapIndexed { index, speaker ->
        val match = bestMatch(bestScores(speaker.centroid, vectors, rejected = emptySet()))
        val link = resolveLink(OccurrenceLink(speakerId = null, link = SpeakerLink.NONE), match)
        OccurrenceDraft(
            ordinal = index + 1,
            speakerId = link.speakerId,
            link = link.link,
            totalSpeechMs = speaker.speechMs,
            sampleStartMs = speaker.sample.startMs,
            sampleEndMs = speaker.sample.endMs,
            centroid = speaker.centroid,
        )
    }
}

private fun validateTurns(turns: List<SpeechTurn>) {
    val dimension = turns.firstOrNull()?.embedding?.size ?: return
    require(dimension > 0) { "Speech turns need non-empty embeddings." }
    for (turn in turns) {
        require(turn.startMs >= 0 && turn.endMs > turn.startMs) { "A speech turn needs a positive time range." }
        require(turn.embedding.size == dimension) { "Speech turns need embeddings of one dimension." }
    }
}

private class PlannedSpeaker(val sample: TimeRange, val speechMs: Long, val centroid: FloatArray)
