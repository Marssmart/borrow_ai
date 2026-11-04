package org.deer.borrowai

import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.service.SpeakerService

/** What the Speakers section of a memory detail shows (section 9.2). */
internal sealed interface SpeakersSectionState {
    data class Cards(val cards: List<SpeakerCard>) : SpeakersSectionState

    /** Analysis finished for this memory and found no speakers. */
    data object NoneDetected : SpeakersSectionState
}

/** One speaker in one memory. [speechMillis] is the speaker's total speech time. */
internal data class SpeakerCard(
    val label: SpeakerLabel,
    val speechMillis: Long,
    val audio: SpeakerAudio,
)

internal sealed interface SpeakerLabel {
    /** The library name of a matched speaker. */
    data class Named(val name: String) : SpeakerLabel

    /** "Speaker N" for an unknown speaker. [ordinal] is 1-based. */
    data class Unknown(val ordinal: Int) : SpeakerLabel
}

internal sealed interface SpeakerAudio {
    /** Plays this speaker's sample from the memory's recording. */
    data class Playable(val sample: SpeakerSample) : SpeakerAudio

    /** The original recording is missing, so there is nothing to play (AC-2.4). */
    data object Unavailable : SpeakerAudio
}

/**
 * One stretch of a memory's recording where one speaker talks, in milliseconds from the start of the recording. Playback
 * turns it into a request for that range (section 10).
 */
internal data class SpeakerSample(val memoryId: String, val startMs: Long, val endMs: Long)

/**
 * The Speakers section for [memory], with cards in ordinal order. Returns null when the section is left out. A memory
 * with no speakers shows "no speakers" only once its analysis is DONE. SKIPPED also covers a feature that is off, and
 * PENDING has not run, so neither can claim that nobody was detected.
 */
internal fun speakersSectionState(
    memory: Memory,
    occurrences: List<SpeakerOccurrence>,
    namesById: Map<String, String>,
    audioAvailable: Boolean,
): SpeakersSectionState? = when {
    occurrences.isNotEmpty() -> SpeakersSectionState.Cards(
        occurrences.sortedBy { it.ordinal }.map { occurrence ->
            SpeakerCard(
                label = occurrence.speakerId?.let { namesById[it] }?.let { SpeakerLabel.Named(it) }
                    ?: SpeakerLabel.Unknown(occurrence.ordinal),
                speechMillis = occurrence.totalSpeechMs,
                audio = if (audioAvailable) {
                    SpeakerAudio.Playable(
                        SpeakerSample(
                            memoryId = memory.id,
                            startMs = occurrence.sampleStartMs,
                            endMs = occurrence.sampleEndMs,
                        ),
                    )
                } else {
                    SpeakerAudio.Unavailable
                },
            )
        },
    )
    memory.speakerStatus == SpeakerStatus.DONE -> SpeakersSectionState.NoneDetected
    else -> null
}

/**
 * Reads the Speakers section for [memory]. Returns null when the section is left out, and also when the speaker store
 * cannot be read, so a failed read never shows a false "no speakers detected".
 */
internal suspend fun loadSpeakersSection(
    speakers: SpeakerService,
    memory: Memory,
    audioAvailable: Boolean,
): SpeakersSectionState? {
    val occurrences = speakers.findOccurrences(memory.id).getOrNull() ?: return null
    val namesById = if (occurrences.all { it.speakerId == null }) {
        emptyMap()
    } else {
        speakers.findSpeakers().getOrNull()?.associate { it.id to it.name } ?: return null
    }
    return speakersSectionState(memory, occurrences, namesById, audioAvailable)
}

/** Speech time as minutes and seconds, such as "2:10". Partial seconds are dropped. */
internal fun formatSpeechDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / MILLIS_PER_SECOND
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

private const val MILLIS_PER_SECOND = 1_000L
