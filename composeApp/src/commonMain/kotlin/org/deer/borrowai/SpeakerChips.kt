package org.deer.borrowai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.speaker_more_chip
import borrowai.composeapp.generated.resources.speaker_more_speakers
import borrowai.composeapp.generated.resources.speaker_pending_description
import borrowai.composeapp.generated.resources.speaker_unknown_label
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The speaker chips under a memory's summary (section 9.1). Tapping a speaker's chip opens the memory detail at that
 * speaker. Tapping "+N" opens it without a speaker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerChipsRow(
    state: SpeakerChipsState,
    onOpenSpeaker: (Int?) -> Unit,
) {
    when (state) {
        SpeakerChipsState.Pending -> PendingSpeakerChip()

        is SpeakerChipsState.Speakers -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_SPACING),
            verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_SPACING),
        ) {
            state.chips.forEach { chip ->
                SpeakerChipView(chip = chip, onClick = { onOpenSpeaker(chip.ordinal) })
            }
            if (state.overflow > 0) {
                MoreSpeakersChip(count = state.overflow, onClick = { onOpenSpeaker(null) })
            }
        }
    }
}

@Composable
private fun SpeakerChipView(chip: SpeakerChip, onClick: () -> Unit) {
    val color = Color(speakerPalette[chip.colorIndex])
    val shape = SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_SHAPE
    val label = when (chip) {
        is SpeakerChip.Recognized -> chip.name
        is SpeakerChip.Unknown -> stringResource(Res.string.speaker_unknown_label, chip.ordinal)
    }
    val tappable = Modifier.clip(shape).clickable(role = Role.Button, onClick = onClick)
    when (chip) {
        is SpeakerChip.Recognized -> Text(
            label,
            modifier = tappable.background(color).padding(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_PADDING),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )

        is SpeakerChip.Unknown -> Text(
            label,
            modifier = tappable.border(1.dp, color, shape).padding(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_PADDING),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MoreSpeakersChip(count: Int, onClick: () -> Unit) {
    val description = pluralStringResource(Res.plurals.speaker_more_speakers, count, count)
    Text(
        stringResource(Res.string.speaker_more_chip, count),
        modifier = Modifier
            .clip(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_PADDING)
            .semantics { contentDescription = description },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** A muted chip with no label that pulses while analysis is queued or running (section 9.1). */
@Composable
private fun PendingSpeakerChip() {
    val pulseAlpha by rememberInfiniteTransition(label = "speaker-pending").animateFloat(
        initialValue = PENDING_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PENDING_PULSE_MILLIS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speaker-pending-alpha",
    )
    val description = stringResource(Res.string.speaker_pending_description)
    Box(
        modifier = Modifier
            .height(PENDING_CHIP_HEIGHT)
            .width(PENDING_CHIP_WIDTH)
            .clip(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_SHAPE)
            .graphicsLayer { this.alpha = pulseAlpha }
            .background(PENDING_CHIP_COLOR)
            .semantics { contentDescription = description },
    )
}

private val PENDING_CHIP_COLOR = Color(0xFFE3E7E9)
private val PENDING_CHIP_HEIGHT = 24.dp
private val PENDING_CHIP_WIDTH = 40.dp
private const val PENDING_MIN_ALPHA = 0.5f
private const val PENDING_PULSE_MILLIS = 900
