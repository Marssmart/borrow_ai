package org.deer.borrowai

import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackSample
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.SpeakerService

/** What the Speakers section of a memory detail shows (section 9.2). */
internal sealed interface SpeakersSectionState {
    data class Cards(val cards: List<SpeakerCard>) : SpeakersSectionState

    /** Analysis finished for this memory and found no speakers. */
    data object NoneDetected : SpeakersSectionState
}

/** One speaker in one memory. [ordinal] is the 1-based speaker number, and [speechMillis] is the total speech time. */
internal data class SpeakerCard(
    val ordinal: Int,
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
    /** Plays this speaker's sample. [target] is the sample's range in the memory's recording. */
    data class Playable(val target: PlaybackTarget) : SpeakerAudio

    /** The original recording is missing, so there is nothing to play (AC-2.4). */
    data object Unavailable : SpeakerAudio
}

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
                ordinal = occurrence.ordinal,
                label = occurrence.speakerId?.let { namesById[it] }?.let { SpeakerLabel.Named(it) }
                    ?: SpeakerLabel.Unknown(occurrence.ordinal),
                speechMillis = occurrence.totalSpeechMs,
                audio = if (audioAvailable) {
                    SpeakerAudio.Playable(
                        PlaybackTarget(
                            memoryId = memory.id,
                            content = PlaybackContent.SPEAKER_SAMPLE,
                            sample = PlaybackSample(occurrence.sampleStartMs, occurrence.sampleEndMs),
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
