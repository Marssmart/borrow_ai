package org.deer.borrowai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.sp

@Composable
internal fun BorrowCaptureScreen(
    isRecording: Boolean,
    isHoldRecording: Boolean,
    elapsedSeconds: Int,
    transcript: String,
    onToggleRecording: () -> Unit,
    onStartHoldRecording: () -> Unit,
    onStopHoldRecording: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val microphoneSize = maxWidth * 0.8f
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    transcript,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 18.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    lineHeight = 25.sp,
                )
            }

            MicrophoneButton(
                buttonSize = microphoneSize,
                isRecording = isRecording,
                isHoldRecording = isHoldRecording,
                elapsedSeconds = elapsedSeconds,
                onToggleRecording = onToggleRecording,
                onStartHoldRecording = onStartHoldRecording,
                onStopHoldRecording = onStopHoldRecording,
            )

            Column(
                modifier = Modifier.fillMaxWidth().weight(0.45f).padding(top = 16.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = if (isRecording) {
                        if (isHoldRecording) "RELEASE TO STOP" else "TAP TO STOP"
                    } else {
                        "TAP TO RECORD  ·  HOLD UNTIL RELEASE"
                    },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    color = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "SWIPE LEFT FOR SAVED MEMORIES",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    letterSpacing = 0.7.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
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
    val infiniteTransition = rememberInfiniteTransition(label = "mic-pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.1f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRecording) 650 else 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-scale",
    )
    val pulseWave by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRecording) 1_300 else 1_600),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mic-ring",
    )
    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) MaterialTheme.colorScheme.error else Color.White,
        animationSpec = tween(220),
        label = "mic-color",
    )
    val buttonContentColor = if (isRecording) Color.White else MaterialTheme.colorScheme.primary
    val pulseColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val buttonBorderColor = if (isRecording) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Box(
        modifier = Modifier.size(buttonSize * MAX_BUTTON_PULSE_SCALE + 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { clip = false }) {
            val buttonRadius = buttonSize.toPx() * pulse / 2f - 10.dp.toPx()
            drawCircle(
                color = Color.White.copy(alpha = 0.58f),
                radius = buttonRadius + 4.dp.toPx(),
                style = Stroke(width = 8.dp.toPx()),
            )
            drawCircle(
                color = pulseColor.copy(
                    alpha = (if (isRecording) 0.48f else 0.34f) * (1f - pulseWave),
                ),
                radius = buttonRadius + 8.dp.toPx() + 28.dp.toPx() * pulseWave,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Surface(
            modifier = Modifier.size(buttonSize * pulse).padding(10.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = if (isRecording) "Stop recording" else "Start recording"
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
                border = BorderStroke(width = 8.dp, color = buttonBorderColor),
                shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MicrophoneGlyph(
                    modifier = Modifier.size(62.dp),
                    color = buttonContentColor,
                )
                Spacer(Modifier.height(11.dp))
                Text(
                    if (isRecording) "RECORDING" else "TAP OR HOLD",
                    color = buttonContentColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                if (isRecording) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatDuration(elapsedSeconds),
                        color = buttonContentColor.copy(alpha = 0.9f),
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
        val stroke = size.minDimension * 0.055f
        val micWidth = size.width * 0.25f
        val micHeight = size.height * 0.43f
        val micLeft = (size.width - micWidth) / 2f
        val micTop = size.height * 0.08f

        drawRoundRect(
            color = color,
            topLeft = Offset(micLeft, micTop),
            size = Size(micWidth, micHeight),
            cornerRadius = CornerRadius(micWidth / 2f),
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.22f, size.height * 0.30f),
            size = Size(size.width * 0.56f, size.height * 0.48f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, size.height * 0.76f),
            end = Offset(size.width / 2f, size.height * 0.91f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.34f, size.height * 0.92f),
            end = Offset(size.width * 0.66f, size.height * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

private const val MAX_BUTTON_PULSE_SCALE = 1.1f
