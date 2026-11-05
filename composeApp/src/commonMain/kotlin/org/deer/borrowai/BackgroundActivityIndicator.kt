package org.deer.borrowai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.background_activity_cleanup
import borrowai.composeapp.generated.resources.background_activity_categorization
import borrowai.composeapp.generated.resources.background_activity_model_download
import borrowai.composeapp.generated.resources.background_activity_speaker_analysis
import borrowai.composeapp.generated.resources.background_activity_summary
import borrowai.composeapp.generated.resources.background_activity_transcription
import borrowai.composeapp.generated.resources.background_activity_transcription_progress
import borrowai.composeapp.generated.resources.background_indicator_more
import org.deer.borrowai.data.service.background.BackgroundActivity
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How many activity names the indicator spells out. The rest are shown as a count. */
internal const val BACKGROUND_INDICATOR_MAX_NAMED = 2

/**
 * What the indicator shows. It is visible only when the user has the setting on and some background work is running.
 * [activities] is in display order.
 */
internal data class BackgroundActivityIndicatorState(
    val visible: Boolean,
    val activities: List<BackgroundActivity>,
    val transcriptionProgressPercent: Int? = null,
) {
    val namedActivities: List<BackgroundActivity> get() = activities.take(BACKGROUND_INDICATOR_MAX_NAMED)

    /** Activities beyond the named ones, shown as "+N". */
    val hiddenCount: Int get() = (activities.size - BACKGROUND_INDICATOR_MAX_NAMED).coerceAtLeast(0)
}

internal fun backgroundActivityIndicatorState(
    activities: Set<BackgroundActivity>,
    showIndicator: Boolean,
    transcriptionProgressPercent: Int? = null,
): BackgroundActivityIndicatorState {
    val ordered = BackgroundActivity.entries.filter { it in activities }
    return BackgroundActivityIndicatorState(
        visible = showIndicator && ordered.isNotEmpty(),
        activities = ordered,
        transcriptionProgressPercent = transcriptionProgressPercent
            ?.takeIf { BackgroundActivity.TRANSCRIPTION in ordered && it in 1..99 },
    )
}

/**
 * A small pill at the top of the screen with a spinner and the names of the running activities, such as
 * "Transcribing · Summarizing +1". It fades out with its last content, so the names do not blank during the exit.
 */
@Composable
internal fun BackgroundActivityIndicator(
    state: BackgroundActivityIndicatorState,
    modifier: Modifier = Modifier,
) {
    var lastShown by remember { mutableStateOf(state) }
    if (state.visible) lastShown = state
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        val names = lastShown.namedActivities.map { activity ->
            val progress = lastShown.transcriptionProgressPercent
            if (activity == BackgroundActivity.TRANSCRIPTION && progress != null) {
                stringResource(Res.string.background_activity_transcription_progress, progress)
            } else {
                stringResource(activity.labelResource())
            }
        }
        val text = buildString {
            append(names.joinToString(" · "))
            if (lastShown.hiddenCount > 0) {
                append(" ")
                append(stringResource(Res.string.background_indicator_more, lastShown.hiddenCount))
            }
        }
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun BackgroundActivity.labelResource(): StringResource = when (this) {
    BackgroundActivity.MODEL_DOWNLOAD -> Res.string.background_activity_model_download
    BackgroundActivity.TRANSCRIPTION -> Res.string.background_activity_transcription
    BackgroundActivity.SPEAKER_ANALYSIS -> Res.string.background_activity_speaker_analysis
    BackgroundActivity.SUMMARY -> Res.string.background_activity_summary
    BackgroundActivity.CATEGORIZATION -> Res.string.background_activity_categorization
    BackgroundActivity.CLEANUP -> Res.string.background_activity_cleanup
}
