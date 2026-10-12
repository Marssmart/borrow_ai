package org.deer.borrowai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.deer.borrowai.data.service.MediaSessionService
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.capture_elapsed_duration
import borrowai.composeapp.generated.resources.capture_recording_label
import borrowai.composeapp.generated.resources.capture_release_to_stop
import borrowai.composeapp.generated.resources.capture_start_recording_description
import borrowai.composeapp.generated.resources.capture_stop_recording_description
import borrowai.composeapp.generated.resources.capture_swipe_saved_memories
import borrowai.composeapp.generated.resources.capture_tap_or_hold
import borrowai.composeapp.generated.resources.capture_tap_to_record_hold
import borrowai.composeapp.generated.resources.capture_tap_to_stop
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BorrowCaptureScreen(
    mediaSessionService: MediaSessionService,
) {
    val stateHolder = remember(mediaSessionService) { CaptureUiStateHolder(mediaSessionService) }
    val uiState by stateHolder.uiState.collectAsState()
    LaunchedEffect(stateHolder) {
        stateHolder.observeMediaSession()
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val microphoneSize = maxWidth * CaptureUiStateHolder.MICROPHONE_WIDTH_FRACTION
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = CaptureUiStateHolder.CONTENT_HORIZONTAL_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(CaptureUiStateHolder.TRANSCRIPT_WEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    uiState.transcript,
                    modifier = Modifier.fillMaxWidth()
                        .padding(
                            horizontal = CaptureUiStateHolder.TRANSCRIPT_HORIZONTAL_PADDING,
                            vertical = CaptureUiStateHolder.TRANSCRIPT_VERTICAL_PADDING,
                        )
                        .verticalScroll(stateHolder.transcriptScrollState),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    lineHeight = CaptureUiStateHolder.TRANSCRIPT_LINE_HEIGHT,
                )
            }

            MicrophoneButton(
                buttonSize = microphoneSize,
                isRecording = uiState.isRecording,
                isHoldRecording = uiState.isHoldRecording,
                elapsedSeconds = uiState.elapsedSeconds,
                onToggleRecording = stateHolder::toggleRecording,
                onStartHoldRecording = stateHolder::startHoldRecording,
                onStopHoldRecording = stateHolder::stopHoldRecording,
            )

            Column(
                modifier = Modifier.fillMaxWidth()
                    .weight(CaptureUiStateHolder.FOOTER_WEIGHT)
                    .padding(
                        top = CaptureUiStateHolder.FOOTER_TOP_PADDING,
                        bottom = CaptureUiStateHolder.FOOTER_BOTTOM_PADDING,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CaptureUiStateHolder.FOOTER_ITEM_SPACING),
            ) {
                Text(
                    text = if (uiState.isRecording) {
                        if (uiState.isHoldRecording) {
                            stringResource(Res.string.capture_release_to_stop)
                        } else {
                            stringResource(Res.string.capture_tap_to_stop)
                        }
                    } else {
                        stringResource(Res.string.capture_tap_to_record_hold)
                    },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = CaptureUiStateHolder.STATUS_LETTER_SPACING,
                    color = if (uiState.isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(Res.string.capture_swipe_saved_memories),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = CaptureUiStateHolder.MEMORY_HINT_FONT_SIZE,
                    letterSpacing = CaptureUiStateHolder.MEMORY_HINT_LETTER_SPACING,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = CaptureUiStateHolder.MEMORY_HINT_ALPHA,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MicrophoneButton(
    buttonSize: Dp,
    isRecording: Boolean,
    isHoldRecording: Boolean,
    elapsedSeconds: Int,
    onToggleRecording: () -> Unit,
    onStartHoldRecording: () -> Unit,
    onStopHoldRecording: () -> Unit,
) {
    val latestIsRecording = rememberUpdatedState(isRecording)
    val latestOnToggle = rememberUpdatedState(onToggleRecording)
    val latestOnHoldStart = rememberUpdatedState(onStartHoldRecording)
    val latestOnHoldStop = rememberUpdatedState(onStopHoldRecording)
    val microphoneDescription = stringResource(
        if (isRecording) Res.string.capture_stop_recording_description
        else Res.string.capture_start_recording_description,
    )
    val infiniteTransition = rememberInfiniteTransition(label = "mic-pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) {
            CaptureUiStateHolder.RECORDING_BUTTON_PULSE_SCALE
        } else {
            CaptureUiStateHolder.IDLE_BUTTON_PULSE_SCALE
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                if (isRecording) CaptureUiStateHolder.RECORDING_BUTTON_PULSE_MILLIS
                else CaptureUiStateHolder.IDLE_BUTTON_PULSE_MILLIS,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-scale",
    )
    val pulseWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                if (isRecording) CaptureUiStateHolder.RECORDING_RING_PULSE_MILLIS
                else CaptureUiStateHolder.IDLE_RING_PULSE_MILLIS,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mic-ring",
    )
    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) MaterialTheme.colorScheme.error else Color.White,
        animationSpec = tween(CaptureUiStateHolder.COLOR_TRANSITION_MILLIS),
        label = "mic-color",
    )
    val buttonContentColor = if (isRecording) Color.White else MaterialTheme.colorScheme.primary
    val pulseColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val buttonBorderColor = if (isRecording) {
        MaterialTheme.colorScheme.error.copy(alpha = CaptureUiStateHolder.RECORDING_BORDER_ALPHA)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Box(
        modifier = Modifier.size(
            buttonSize * CaptureUiStateHolder.RECORDING_BUTTON_PULSE_SCALE +
                CaptureUiStateHolder.BUTTON_EXTRA_SIZE,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { clip = false }) {
            val buttonRadius = buttonSize.toPx() * pulse / 2f -
                CaptureUiStateHolder.BUTTON_RADIUS_INSET.toPx()
            drawCircle(
                color = Color.White.copy(alpha = CaptureUiStateHolder.BUTTON_HALO_ALPHA),
                radius = buttonRadius + CaptureUiStateHolder.BUTTON_HALO_EXTRA_RADIUS.toPx(),
                style = Stroke(width = CaptureUiStateHolder.BUTTON_BORDER_WIDTH.toPx()),
            )
            drawCircle(
                color = pulseColor.copy(
                    alpha = (
                        if (isRecording) CaptureUiStateHolder.RECORDING_RING_ALPHA
                        else CaptureUiStateHolder.IDLE_RING_ALPHA
                    ) * (1f - pulseWave),
                ),
                radius = buttonRadius + CaptureUiStateHolder.RING_INITIAL_RADIUS.toPx() +
                    CaptureUiStateHolder.RING_WAVE_EXTENT.toPx() * pulseWave,
                style = Stroke(width = CaptureUiStateHolder.RING_STROKE_WIDTH.toPx()),
            )
        }
        Surface(
            modifier = Modifier.size(buttonSize * pulse)
                .padding(CaptureUiStateHolder.BUTTON_CONTENT_INSET)
                .semantics {
                    role = Role.Button
                    contentDescription = microphoneDescription
                    onClick {
                        latestOnToggle.value()
                        true
                    }
                }
                .pointerInput(Unit) {
                    var holdStarted = false
                    detectTapGestures(
                        onPress = {
                            holdStarted = false
                            val released = tryAwaitRelease()
                            if (holdStarted) {
                                latestOnHoldStop.value()
                            } else if (released) {
                                latestOnToggle.value()
                            }
                        },
                        onLongPress = {
                            if (!latestIsRecording.value) {
                                holdStarted = true
                                latestOnHoldStart.value()
                            }
                        },
                    )
                },
                shape = CircleShape,
                color = buttonColor,
                border = BorderStroke(
                    width = CaptureUiStateHolder.BUTTON_BORDER_WIDTH,
                    color = buttonBorderColor,
                ),
                shadowElevation = CaptureUiStateHolder.BUTTON_SHADOW_ELEVATION,
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(CaptureUiStateHolder.BUTTON_CONTENT_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MicrophoneGlyph(
                    modifier = Modifier.size(CaptureUiStateHolder.BUTTON_GLYPH_SIZE),
                    color = buttonContentColor,
                )
                Spacer(Modifier.height(CaptureUiStateHolder.BUTTON_GLYPH_SPACING))
                Text(
                    stringResource(
                        if (isRecording) Res.string.capture_recording_label
                        else Res.string.capture_tap_or_hold,
                    ),
                    color = buttonContentColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = CaptureUiStateHolder.BUTTON_CONTENT_FONT_SPACING,
                )
                if (isRecording) {
                    Spacer(Modifier.height(CaptureUiStateHolder.RECORDING_DURATION_SPACING))
                    val durationParts = formatDurationParts(elapsedSeconds)
                    Text(
                        stringResource(
                            Res.string.capture_elapsed_duration,
                            durationParts.first,
                            durationParts.second,
                        ),
                        color = buttonContentColor.copy(alpha = CaptureUiStateHolder.RECORDING_TEXT_ALPHA),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MicrophoneGlyph(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = size.minDimension * CaptureUiStateHolder.GLYPH_STROKE_WIDTH_FRACTION
        val micWidth = size.width * CaptureUiStateHolder.GLYPH_MIC_WIDTH_FRACTION
        val micHeight = size.height * CaptureUiStateHolder.GLYPH_MIC_HEIGHT_FRACTION
        val micLeft = (size.width - micWidth) / 2f
        val micTop = size.height * CaptureUiStateHolder.GLYPH_MIC_TOP_FRACTION

        drawRoundRect(
            color = color,
            topLeft = Offset(micLeft, micTop),
            size = Size(micWidth, micHeight),
            cornerRadius = CornerRadius(micWidth / 2f),
        )
        drawArc(
            color = color,
            startAngle = CaptureUiStateHolder.GLYPH_ARCH_START_ANGLE,
            sweepAngle = CaptureUiStateHolder.GLYPH_ARCH_SWEEP_ANGLE,
            useCenter = false,
            topLeft = Offset(
                size.width * CaptureUiStateHolder.GLYPH_ARCH_LEFT_FRACTION,
                size.height * CaptureUiStateHolder.GLYPH_ARCH_TOP_FRACTION,
            ),
            size = Size(
                size.width * CaptureUiStateHolder.GLYPH_ARCH_WIDTH_FRACTION,
                size.height * CaptureUiStateHolder.GLYPH_ARCH_HEIGHT_FRACTION,
            ),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(
                size.width / 2f,
                size.height * CaptureUiStateHolder.GLYPH_STEM_TOP_FRACTION,
            ),
            end = Offset(
                size.width / 2f,
                size.height * CaptureUiStateHolder.GLYPH_STEM_BOTTOM_FRACTION,
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(
                size.width * CaptureUiStateHolder.GLYPH_BASE_LEFT_FRACTION,
                size.height * CaptureUiStateHolder.GLYPH_BASE_Y_FRACTION,
            ),
            end = Offset(
                size.width * CaptureUiStateHolder.GLYPH_BASE_RIGHT_FRACTION,
                size.height * CaptureUiStateHolder.GLYPH_BASE_Y_FRACTION,
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun formatDurationParts(totalSeconds: Int): Pair<String, String> {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return minutes.toString().padStart(2, '0') to seconds.toString().padStart(2, '0')
}
