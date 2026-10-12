package org.deer.borrowai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    speechModelName: String,
    languageModelName: String,
    isRecording: Boolean,
    isHoldRecording: Boolean,
    elapsedSeconds: Int,
    transcript: String,
    onToggleRecording: () -> Unit,
    onStartHoldRecording: () -> Unit,
    onStopHoldRecording: () -> Unit,
    onOpenMemories: () -> Unit,
    onChangeModels: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(rememberHorizontalPageSwipe(onSwipeLeft = onOpenMemories)),
    ) {
        val microphoneSize = maxWidth * 0.8f
        Column(modifier = Modifier.fillMaxSize()) {
            CaptureHeader(
                onOpenMemories = onOpenMemories,
                onChangeModels = onChangeModels,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp),
            )

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp)) {
                Text(
                    "Capture a thought",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "A live transcription preview, powered by mock data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SelectedModelLabels(
                speechModelName = speechModelName,
                languageModelName = languageModelName,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 10.dp),
            )

            TranscriptCard(
                transcript = transcript,
                isRecording = isRecording,
                modifier = Modifier.fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 22.dp)
                    .padding(top = 12.dp, bottom = 4.dp),
            )

            Box(
                modifier = Modifier.fillMaxWidth().weight(1.25f),
                contentAlignment = Alignment.Center,
            ) {
                MicrophoneButton(
                    size = microphoneSize,
                    isRecording = isRecording,
                    isHoldRecording = isHoldRecording,
                    elapsedSeconds = elapsedSeconds,
                    onToggleRecording = onToggleRecording,
                    onStartHoldRecording = onStartHoldRecording,
                    onStopHoldRecording = onStopHoldRecording,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
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
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "SWIPE LEFT FOR SAVED MEMORIES",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    letterSpacing = 0.7.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun CaptureHeader(
    onOpenMemories: () -> Unit,
    onChangeModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("B", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text("BorrowAI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "DEMO",
                modifier = Modifier.clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenMemories) {
                Text("Memories")
            }
            TextButton(onClick = onChangeModels) {
                Text("Models")
            }
        }
    }
}

@Composable
private fun SelectedModelLabels(
    speechModelName: String,
    languageModelName: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModelLabel("VOICE", speechModelName)
        ModelLabel("LLM", languageModelName)
    }
}

@Composable
private fun ModelLabel(kind: String, name: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                kind,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TranscriptCard(
    transcript: String,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape).background(
                        if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (isRecording) "LIVE MOCK TRANSCRIPTION" else "SAMPLE TRANSCRIPTION",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "PREVIEW",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                transcript,
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 25.sp,
            )
        }
    }
}

@Composable
private fun MicrophoneButton(
    size: Dp,
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
    val pulse by rememberInfiniteTransition(label = "mic-pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.035f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-scale",
    )
    val buttonColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier.size(size).graphicsLayer {
            scaleX = pulse
            scaleY = pulse
        },
        contentAlignment = Alignment.Center,
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier.fillMaxSize().padding(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f)),
            )
        }
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp)
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
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MicrophoneGlyph(
                    modifier = Modifier.size(62.dp),
                    color = Color.White,
                )
                Spacer(Modifier.height(11.dp))
                Text(
                    if (isRecording) "RECORDING" else "TAP OR HOLD",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                if (isRecording) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatDuration(elapsedSeconds),
                        color = Color.White.copy(alpha = 0.9f),
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
