package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.speaker_audio_unavailable
import borrowai.composeapp.generated.resources.speaker_no_speakers_detected
import borrowai.composeapp.generated.resources.speaker_play_sample
import borrowai.composeapp.generated.resources.speaker_section_title
import borrowai.composeapp.generated.resources.speaker_speech_duration
import borrowai.composeapp.generated.resources.speaker_stop_sample
import borrowai.composeapp.generated.resources.speaker_unknown_label
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.SpeakerService
import org.jetbrains.compose.resources.stringResource

/**
 * The Speakers section of a memory detail, below the transcript (US-2). It reloads when the speaker store changes. Callers
 * leave it out where speaker recognition is not supported. A list chip sets [focusedSpeakerOrdinal] to scroll to that card.
 */
@Composable
internal fun SpeakersSection(
    memory: Memory,
    speakers: SpeakerService,
    hasRecordingFile: (Memory) -> Boolean,
    playingTarget: PlaybackTarget?,
    onPlaySample: (PlaybackTarget) -> Unit,
    focusedSpeakerOrdinal: Int?,
    onSpeakerFocusHandled: () -> Unit,
) {
    val state by produceState<SpeakersSectionState?>(initialValue = null, memory, speakers) {
        speakers.changes.onStart { emit(Unit) }.collect {
            value = loadSpeakersSection(
                speakers = speakers,
                memory = memory,
                audioAvailable = hasRecordingFile(memory),
            )
        }
    }
    val focusRequester = remember { BringIntoViewRequester() }
    val focusedCardShown = (state as? SpeakersSectionState.Cards)
        ?.cards?.any { it.ordinal == focusedSpeakerOrdinal } == true
    // Cleared once the card is in view, so a later reload of the section does not scroll again.
    LaunchedEffect(focusedSpeakerOrdinal, focusedCardShown) {
        if (focusedCardShown) {
            focusRequester.bringIntoView()
            onSpeakerFocusHandled()
        }
    }
    val section = state ?: return
    Column(verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_TEXT_SECTION_SPACING)) {
        Text(
            stringResource(Res.string.speaker_section_title),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = SavedMemoriesStateHolder.MEMORY_TEXT_LABEL_LETTER_SPACING,
        )
        when (section) {
            is SpeakersSectionState.Cards -> section.cards.forEach { card ->
                val focusModifier = if (card.ordinal == focusedSpeakerOrdinal) {
                    Modifier.bringIntoViewRequester(focusRequester)
                } else {
                    Modifier
                }
                Box(focusModifier) {
                    SpeakerCardPanel(card = card, playingTarget = playingTarget, onPlaySample = onPlaySample)
                }
            }

            SpeakersSectionState.NoneDetected -> AuroraPanel(
                containerColor = Color.White.copy(alpha = SavedMemoriesStateHolder.MEMORY_DETAIL_HEADER_SURFACE_ALPHA),
            ) {
                Text(
                    stringResource(Res.string.speaker_no_speakers_detected),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SpeakerCardPanel(
    card: SpeakerCard,
    playingTarget: PlaybackTarget?,
    onPlaySample: (PlaybackTarget) -> Unit,
) {
    AuroraPanel(
        containerColor = Color.White.copy(alpha = SavedMemoriesStateHolder.MEMORY_DETAIL_HEADER_SURFACE_ALPHA),
    ) {
        Text(
            speakerLabelText(card.label),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(Res.string.speaker_speech_duration, formatSpeechDuration(card.speechMillis)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        when (val audio = card.audio) {
            is SpeakerAudio.Playable -> PlaybackButton(
                playing = playingTarget == audio.target,
                playLabel = Res.string.speaker_play_sample,
                playingLabel = Res.string.speaker_stop_sample,
                onClick = { onPlaySample(audio.target) },
            )

            SpeakerAudio.Unavailable -> Text(
                stringResource(Res.string.speaker_audio_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

@Composable
private fun speakerLabelText(label: SpeakerLabel): String = when (label) {
    is SpeakerLabel.Named -> label.name
    is SpeakerLabel.Unknown -> stringResource(Res.string.speaker_unknown_label, label.ordinal)
}
