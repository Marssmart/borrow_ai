package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.speaker_audio_unavailable
import borrowai.composeapp.generated.resources.speaker_no_speakers_detected
import borrowai.composeapp.generated.resources.speaker_play_sample
import borrowai.composeapp.generated.resources.speaker_section_title
import borrowai.composeapp.generated.resources.speaker_speech_duration
import borrowai.composeapp.generated.resources.speaker_stop_sample
import borrowai.composeapp.generated.resources.speaker_unknown_label
import borrowai.composeapp.generated.resources.speaker_management_assign
import borrowai.composeapp.generated.resources.speaker_management_change
import borrowai.composeapp.generated.resources.speaker_management_matched
import borrowai.composeapp.generated.resources.speaker_management_not_this_person
import borrowai.composeapp.generated.resources.speaker_management_assigned
import borrowai.composeapp.generated.resources.speaker_management_not_assigned
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
    onManageOccurrence: (org.deer.borrowai.data.domain.SpeakerOccurrence, Boolean) -> Unit,
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
    val focusedCardSize = remember(focusedSpeakerOrdinal) { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val shadowOutsetPx = with(density) {
        (SavedMemoriesStateHolder.MEMORY_PANEL_DEFAULT_ELEVATION * 2f).toPx()
    }
    val focusedCardShown = (state as? SpeakersSectionState.Cards)
        ?.cards?.any { it.ordinal == focusedSpeakerOrdinal } == true
    // Cleared once the card is in view, so a later reload of the section does not scroll again.
    LaunchedEffect(focusedSpeakerOrdinal, focusedCardShown, focusedCardSize.value, shadowOutsetPx) {
        val size = focusedCardSize.value
        if (focusedCardShown && size != IntSize.Zero) {
            // Include the shadow spill so the detail scroller does not clip it at the viewport edge.
            focusRequester.bringIntoView(speakerCardBringIntoViewRect(size, shadowOutsetPx))
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
                    Modifier
                        .onSizeChanged { focusedCardSize.value = it }
                        .bringIntoViewRequester(focusRequester)
                } else {
                    Modifier
                }
                Box(focusModifier) {
                    SpeakerCardPanel(
                        card = card,
                        playingTarget = playingTarget,
                        onPlaySample = onPlaySample,
                        onManageOccurrence = onManageOccurrence,
                    )
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
    onManageOccurrence: (org.deer.borrowai.data.domain.SpeakerOccurrence, Boolean) -> Unit,
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
        card.occurrence?.let { occurrence ->
            Text(
                stringResource(
                    when (occurrence.link) {
                        org.deer.borrowai.data.domain.SpeakerLink.NONE -> Res.string.speaker_management_not_assigned
                        org.deer.borrowai.data.domain.SpeakerLink.AUTO -> Res.string.speaker_management_matched
                        org.deer.borrowai.data.domain.SpeakerLink.MANUAL -> Res.string.speaker_management_assigned
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
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
        card.occurrence?.let { occurrence ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onManageOccurrence(occurrence, false) }) {
                    Text(
                        stringResource(
                            if (occurrence.speakerId == null) Res.string.speaker_management_assign
                            else Res.string.speaker_management_change,
                        ),
                    )
                }
                if (occurrence.link == org.deer.borrowai.data.domain.SpeakerLink.AUTO) {
                    TextButton(onClick = { onManageOccurrence(occurrence, true) }) {
                        Text(stringResource(Res.string.speaker_management_not_this_person))
                    }
                }
            }
        }
    }
}

@Composable
private fun speakerLabelText(label: SpeakerLabel): String = when (label) {
    is SpeakerLabel.Named -> label.name
    is SpeakerLabel.Unknown -> stringResource(Res.string.speaker_unknown_label, label.ordinal)
}

internal fun speakerCardBringIntoViewRect(size: IntSize, shadowOutsetPx: Float): Rect = Rect(
    left = -shadowOutsetPx,
    top = -shadowOutsetPx,
    right = size.width + shadowOutsetPx,
    bottom = size.height + shadowOutsetPx,
)
