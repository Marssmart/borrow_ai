package org.deer.borrowai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_duration_unknown
import borrowai.composeapp.generated.resources.memory_pause_recording
import borrowai.composeapp.generated.resources.memory_play_recording
import borrowai.composeapp.generated.resources.memory_playback_back_15
import borrowai.composeapp.generated.resources.memory_playback_forward_15
import borrowai.composeapp.generated.resources.memory_playback_paused_by_other_app
import borrowai.composeapp.generated.resources.memory_playback_position_elapsed
import borrowai.composeapp.generated.resources.memory_playback_position_of
import org.deer.borrowai.data.service.MediaSessionState
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Seek bar, time labels, skip buttons, the play or pause button, and the speed button in the memory detail view.
 * [playback] is this memory's playback, playing or paused, or null when it is not the active playback. The seek bar
 * and the skips need an active playback, and the seek bar and the forward skip also need a known [durationMillis].
 * The paused notice shows when another app paused the playback.
 */
@Composable
internal fun PlaybackPanel(
    playback: MediaSessionState.Playing?,
    durationMillis: Long?,
    speed: Float,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onSpeedSelected: (Float) -> Unit,
) {
    val knownDurationMillis = durationMillis?.takeIf { it > 0L }
    val isPlaying = playback != null && !playback.paused
    val canSeek = playback != null && knownDurationMillis != null
    // The position shown while the user drags the seek bar. It is sent to the player when the drag ends.
    var dragMillis by remember { mutableStateOf<Long?>(null) }
    val positionMillis = (dragMillis ?: playback?.elapsedMillis ?: 0L).coerceIn(0L, knownDurationMillis ?: 0L)
    val positionLabel = formatPlaybackTime(positionMillis)
    val totalLabel = knownDurationMillis?.let(::formatPlaybackTime)
    val sliderDescription = if (totalLabel != null) {
        stringResource(Res.string.memory_playback_position_of, positionLabel, totalLabel)
    } else {
        stringResource(Res.string.memory_playback_position_elapsed, positionLabel)
    }
    val playLabel = stringResource(
        if (isPlaying) Res.string.memory_pause_recording else Res.string.memory_play_recording,
    )

    Column(verticalArrangement = Arrangement.spacedBy(PLAYBACK_PANEL_SPACING)) {
        // Material 3 sets its own state description, so the times are in the content description instead.
        Slider(
            value = positionMillis.toFloat(),
            onValueChange = { dragMillis = it.toLong() },
            onValueChangeFinished = {
                dragMillis?.let(onSeek)
                dragMillis = null
            },
            valueRange = 0f..(knownDurationMillis ?: 1L).toFloat(),
            enabled = canSeek,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = sliderDescription },
        )
        Row(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                positionLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                totalLabel ?: stringResource(Res.string.memory_duration_unknown),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The transport shares the row with the speed button, so the two never overlap on narrow screens.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                SkipButton(forward = false, enabled = playback != null, onClick = { onSkip(-SKIP_MILLIS) })
                Spacer(Modifier.width(PLAYBACK_CONTROL_SPACING))
                FilledIconButton(
                    onClick = onTogglePlayback,
                    modifier = Modifier.size(PLAY_BUTTON_SIZE).semantics { contentDescription = playLabel },
                ) {
                    PlayPauseGlyph(playing = isPlaying, color = LocalContentColor.current)
                }
                Spacer(Modifier.width(PLAYBACK_CONTROL_SPACING))
                SkipButton(forward = true, enabled = canSeek, onClick = { onSkip(SKIP_MILLIS) })
            }
            PlaybackSpeedButton(speed = speed, onSpeedSelected = onSpeedSelected)
        }
        if (playback?.pausedByOtherApp == true) {
            Text(
                stringResource(Res.string.memory_playback_paused_by_other_app),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SkipButton(forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        if (forward) Res.string.memory_playback_forward_15 else Res.string.memory_playback_back_15,
    )
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(SKIP_BUTTON_SIZE).semantics { contentDescription = label },
    ) {
        SkipGlyph(forward = forward, color = LocalContentColor.current)
    }
}

@Composable
private fun SkipGlyph(forward: Boolean, color: Color) {
    Box(modifier = Modifier.size(SKIP_GLYPH_SIZE), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawSkipArrow(forward = forward, color = color)
        }
        Text(
            "15",
            modifier = Modifier.clearAndSetSemantics { },
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(PLAY_PAUSE_GLYPH_SIZE)) {
        if (playing) {
            val barWidth = size.width * PAUSE_BAR_WIDTH_FRACTION
            val barHeight = size.height * PAUSE_BAR_HEIGHT_FRACTION
            val barTop = size.height * PAUSE_BAR_TOP_FRACTION
            val corner = CornerRadius(barWidth / 2f)
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width * PAUSE_LEFT_BAR_FRACTION, barTop),
                size = Size(barWidth, barHeight),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width * PAUSE_RIGHT_BAR_FRACTION, barTop),
                size = Size(barWidth, barHeight),
                cornerRadius = corner,
            )
        } else {
            drawPath(
                path = Path().apply {
                    moveTo(size.width * PLAY_TIP_LEFT_FRACTION, size.height * PLAY_TOP_FRACTION)
                    lineTo(size.width * PLAY_TIP_FRACTION, size.height / 2f)
                    lineTo(size.width * PLAY_TIP_LEFT_FRACTION, size.height * PLAY_BOTTOM_FRACTION)
                    close()
                },
                color = color,
            )
        }
    }
}

/** A 270 degree arc with an arrowhead at its end: clockwise for forward, counterclockwise for back. */
private fun DrawScope.drawSkipArrow(forward: Boolean, color: Color) {
    val side = minOf(size.width, size.height)
    val radius = side * SKIP_ARC_RADIUS_FRACTION
    val center = Offset(size.width / 2f, size.height / 2f)
    val sweepDegrees = if (forward) SKIP_SWEEP_DEGREES else -SKIP_SWEEP_DEGREES
    val endRadians = (SKIP_START_DEGREES + sweepDegrees) * DEGREES_TO_RADIANS
    val end = Offset(center.x + radius * cos(endRadians), center.y + radius * sin(endRadians))
    // Unit vector of travel at the end of the arc, in the direction the arc is drawn.
    val travel = if (forward) {
        Offset(-sin(endRadians), cos(endRadians))
    } else {
        Offset(sin(endRadians), -cos(endRadians))
    }
    val across = Offset(-travel.y, travel.x)
    val tip = end + travel * (side * ARROW_LENGTH_FRACTION)
    val halfBase = side * ARROW_HALF_BASE_FRACTION
    drawArc(
        color = color,
        startAngle = SKIP_START_DEGREES,
        sweepAngle = sweepDegrees,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = side * ARC_STROKE_FRACTION),
    )
    drawPath(
        path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(end.x + across.x * halfBase, end.y + across.y * halfBase)
            lineTo(end.x - across.x * halfBase, end.y - across.y * halfBase)
            close()
        },
        color = color,
    )
}

private const val SKIP_MILLIS = 15_000L
private const val SKIP_START_DEGREES = -90f
private const val SKIP_SWEEP_DEGREES = 270f
private const val SKIP_ARC_RADIUS_FRACTION = 0.34f
private const val ARC_STROKE_FRACTION = 0.1f
private const val ARROW_LENGTH_FRACTION = 0.22f
private const val ARROW_HALF_BASE_FRACTION = 0.16f
private const val PAUSE_BAR_WIDTH_FRACTION = 0.2f
private const val PAUSE_BAR_HEIGHT_FRACTION = 0.7f
private const val PAUSE_BAR_TOP_FRACTION = 0.15f
private const val PAUSE_LEFT_BAR_FRACTION = 0.2f
private const val PAUSE_RIGHT_BAR_FRACTION = 0.6f
private const val PLAY_TIP_LEFT_FRACTION = 0.25f
private const val PLAY_TIP_FRACTION = 0.9f
private const val PLAY_TOP_FRACTION = 0.1f
private const val PLAY_BOTTOM_FRACTION = 0.9f
private val DEGREES_TO_RADIANS = (PI / 180.0).toFloat()
private val PLAYBACK_PANEL_SPACING = 4.dp
private val PLAYBACK_CONTROL_SPACING = 14.dp
private val PLAY_BUTTON_SIZE = 56.dp
private val SKIP_BUTTON_SIZE = 48.dp
private val SKIP_GLYPH_SIZE = 26.dp
private val PLAY_PAUSE_GLYPH_SIZE = 24.dp
