package org.deer.borrowai

import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.SPEAKER_PALETTE_SIZE
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.domain.firstFreeSpeakerColor
import org.deer.borrowai.data.service.SpeakerService

/** Most chips one memory row shows. More speakers collapse into "+N" (AC-1.1). */
internal const val MAX_SPEAKER_CHIPS = 3

/** One speaker chip on a memory row (section 9.1). [ordinal] is the 1-based "Speaker N" label. */
internal sealed interface SpeakerChip {
    val ordinal: Int
    val colorIndex: Int

    /** A library speaker: filled with its colour and labelled with its name (AC-1.2). */
    data class Recognized(override val ordinal: Int, override val colorIndex: Int, val name: String) : SpeakerChip

    /** An unknown speaker: outlined in its colour and labelled "Speaker N" (AC-1.2). */
    data class Unknown(override val ordinal: Int, override val colorIndex: Int) : SpeakerChip
}

/** What a memory row shows for speakers. A memory with no speakers and no analysis in progress has no state at all. */
internal sealed interface SpeakerChipsState {
    /** Up to [MAX_SPEAKER_CHIPS] chips, and the count of speakers beyond them. */
    data class Speakers(val chips: List<SpeakerChip>, val overflow: Int) : SpeakerChipsState

    /** Analysis is queued or running for the memory, so a muted chip with no label shows (AC-1.3). */
    data object Pending : SpeakerChipsState
}

/**
 * The chips state for [memory], or null when its row shows nothing (AC-1.4). Pending needs [analysisActive]: with analysis
 * off, a memory is not being analyzed, so no pending chip shows.
 */
internal fun speakerChipsState(
    memory: Memory,
    occurrences: List<SpeakerOccurrence>,
    speakersById: Map<String, Speaker>,
    analysisActive: Boolean,
): SpeakerChipsState? {
    if (occurrences.isEmpty()) {
        val analysisQueued = memory.speakerStatus == SpeakerStatus.PENDING || memory.speakerStatus == SpeakerStatus.ANALYZING
        return if (analysisActive && analysisQueued) SpeakerChipsState.Pending else null
    }
    val ordered = occurrences.sortedBy { it.ordinal }
    val colors = chipColorIndices(ordered, speakersById)
    val chips = ordered.take(MAX_SPEAKER_CHIPS).map { occurrence ->
        val colorIndex = colors.getValue(occurrence.id)
        val speaker = occurrence.speakerId?.let { speakersById[it] }
        if (speaker != null) {
            SpeakerChip.Recognized(occurrence.ordinal, colorIndex, speaker.name)
        } else {
            SpeakerChip.Unknown(occurrence.ordinal, colorIndex)
        }
    }
    return SpeakerChipsState.Speakers(chips = chips, overflow = ordered.size - chips.size)
}

/**
 * The palette index of each occurrence in [ordered], which must be in ordinal order. A library speaker keeps its stored
 * colour. An unknown speaker takes the first colour no other speaker in the memory uses, in order of first speech
 * (section 9.7). Library colours are reserved first, so an unknown speaker never takes a colour a later match will use.
 */
internal fun chipColorIndices(
    ordered: List<SpeakerOccurrence>,
    speakersById: Map<String, Speaker>,
): Map<String, Int> {
    val taken = mutableSetOf<Int>()
    for (occurrence in ordered) {
        occurrence.speakerId?.let { speakersById[it] }?.let { taken += it.colorIndex }
    }
    val colors = mutableMapOf<String, Int>()
    for (occurrence in ordered) {
        val speaker = occurrence.speakerId?.let { speakersById[it] }
        if (speaker != null) {
            colors[occurrence.id] = speaker.colorIndex
        } else {
            val color = firstFreeSpeakerColor(taken) ?: ((occurrence.ordinal - 1) % SPEAKER_PALETTE_SIZE)
            colors[occurrence.id] = color
            taken += color
        }
    }
    return colors
}

/**
 * Chips for every memory in [memories], from one occurrence read and one speaker read however many memories there are
 * (AC-1.5). A store that cannot be read shows no chips, so a failed read never shows a false name or a pending chip.
 */
internal suspend fun loadSpeakerChips(
    speakers: SpeakerService,
    memories: List<Memory>,
    analysisActive: Boolean,
): Map<String, SpeakerChipsState> {
    val occurrences = speakers.findAllOccurrences().getOrNull() ?: return emptyMap()
    val speakersById: Map<String, Speaker> = if (occurrences.all { it.speakerId == null }) {
        emptyMap()
    } else {
        speakers.findSpeakers().getOrNull()?.associateBy { it.id } ?: return emptyMap()
    }
    val occurrencesByMemory = occurrences.groupBy { it.memoryId }
    return buildMap {
        for (memory in memories) {
            speakerChipsState(memory, occurrencesByMemory[memory.id].orEmpty(), speakersById, analysisActive)
                ?.let { put(memory.id, it) }
        }
    }
}
