package org.deer.borrowai.data.service.speaker

import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence

/**
 * Turns the speech turns of one recording into occurrence drafts. Clusters the turns into speakers, picks a sample for
 * each one, and links each to its best library match. A speaker with no sample free of other speakers is dropped, so
 * every draft can be played back. Returns an empty list when there is no speech.
 */
internal fun planSpeakerOccurrences(
    turns: List<SpeechTurn>,
    library: List<SpeakerEmbedding>,
    modelVersion: Int,
    knownSpeakerIds: Set<String>,
    previous: List<SpeakerOccurrence> = emptyList(),
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
        // Earlier analyses of the same audio carry over: a MANUAL link and rejections survive a rebuild (AC-5.3).
        val history = previous.filter { overlaps(it.sampleRange(), ownRanges) }
        planned += PlannedSpeaker(
            sample = sample,
            speechMs = unionLength(ownRanges),
            centroid = meanDirection(own.map { normalized(turns[it].embedding) }),
            manualSpeakerId = history
                .firstOrNull { it.link == SpeakerLink.MANUAL && it.speakerId in knownSpeakerIds }
                ?.speakerId,
            rejected = history.flatMapTo(mutableSetOf<String>()) { it.rejectedSpeakerIds },
        )
    }
    val vectors = speakerVectors(library, modelVersion)
    val links = assignSpeakers(planned, vectors)
    return planned.mapIndexed { index, speaker ->
        OccurrenceDraft(
            ordinal = index + 1,
            speakerId = links[index].speakerId,
            link = links[index].link,
            totalSpeechMs = speaker.speechMs,
            sampleStartMs = speaker.sample.startMs,
            sampleEndMs = speaker.sample.endMs,
            centroid = speaker.centroid,
            rejectedSpeakerIds = speaker.rejected.toList(),
        )
    }
}

/**
 * Links each planned speaker to at most one library speaker, and each library speaker to at most one planned speaker
 * (section 7.2 step 8). MANUAL links go first. The remaining matches are taken best score first, so a stronger match
 * keeps the speaker and a weaker one stays unlinked.
 */
private fun assignSpeakers(planned: List<PlannedSpeaker>, vectors: Map<String, List<FloatArray>>): List<OccurrenceLink> {
    val links = MutableList(planned.size) { OccurrenceLink(speakerId = null, link = SpeakerLink.NONE) }
    val taken = mutableSetOf<String>()
    for ((index, speaker) in planned.withIndex()) {
        val manual = speaker.manualSpeakerId ?: continue
        if (taken.add(manual)) links[index] = OccurrenceLink(manual, SpeakerLink.MANUAL)
    }
    val candidates = planned.indices
        .filter { links[it].link != SpeakerLink.MANUAL }
        .flatMap { index ->
            bestScores(planned[index].centroid, vectors, planned[index].rejected)
                .filterValues { it >= MATCH_THRESHOLD }
                .map { Candidate(index, it.key, it.value) }
        }
        .sortedByDescending { it.score }
    val linked = mutableSetOf<Int>()
    for (candidate in candidates) {
        if (candidate.speakerId in taken || candidate.index in linked) continue
        taken += candidate.speakerId
        linked += candidate.index
        links[candidate.index] = OccurrenceLink(candidate.speakerId, SpeakerLink.AUTO)
    }
    return links
}


private fun validateTurns(turns: List<SpeechTurn>) {
    val dimension = turns.firstOrNull()?.embedding?.size ?: return
    require(dimension > 0) { "Speech turns need non-empty embeddings." }
    for (turn in turns) {
        require(turn.startMs >= 0 && turn.endMs > turn.startMs) { "A speech turn needs a positive time range." }
        require(turn.embedding.size == dimension) { "Speech turns need embeddings of one dimension." }
    }
}

private class PlannedSpeaker(
    val sample: TimeRange,
    val speechMs: Long,
    val centroid: FloatArray,
    val manualSpeakerId: String?,
    val rejected: Set<String>,
)

/** A possible link between a planned speaker and a library speaker, with its similarity. */
private class Candidate(val index: Int, val speakerId: String, val score: Double)

private fun SpeakerOccurrence.sampleRange(): TimeRange = TimeRange(sampleStartMs, sampleEndMs)

private fun overlaps(range: TimeRange, ranges: List<TimeRange>): Boolean =
    ranges.any { it.startMs < range.endMs && range.startMs < it.endMs }
