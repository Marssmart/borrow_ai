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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.ModelSetupFailureReason
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.capture_download_over_mobile_data
import borrowai.composeapp.generated.resources.capture_elapsed_duration
import borrowai.composeapp.generated.resources.capture_model_choosing
import borrowai.composeapp.generated.resources.capture_model_downloading_summarization
import borrowai.composeapp.generated.resources.capture_model_downloading_transcription
import borrowai.composeapp.generated.resources.capture_model_failed_model
import borrowai.composeapp.generated.resources.capture_model_failed_model_detail
import borrowai.composeapp.generated.resources.capture_model_failed_network
import borrowai.composeapp.generated.resources.capture_model_failed_network_detail
import borrowai.composeapp.generated.resources.capture_model_failed_storage
import borrowai.composeapp.generated.resources.capture_model_failed_storage_detail
import borrowai.composeapp.generated.resources.capture_model_loading_transcription
import borrowai.composeapp.generated.resources.capture_model_progress_summarization
import borrowai.composeapp.generated.resources.capture_model_progress_transcription
import borrowai.composeapp.generated.resources.capture_model_waiting_for_network
import borrowai.composeapp.generated.resources.capture_model_waiting_for_unmetered_network
import borrowai.composeapp.generated.resources.capture_model_waiting_for_retry
import borrowai.composeapp.generated.resources.capture_models_unavailable
import borrowai.composeapp.generated.resources.capture_recording_label
import borrowai.composeapp.generated.resources.capture_release_to_stop
import borrowai.composeapp.generated.resources.capture_start_recording_description
import borrowai.composeapp.generated.resources.capture_stop_recording_description
import borrowai.composeapp.generated.resources.capture_swipe_saved_memories
import borrowai.composeapp.generated.resources.capture_tap_or_hold
import borrowai.composeapp.generated.resources.capture_tap_to_record_hold
import borrowai.composeapp.generated.resources.capture_tap_to_stop
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

@Composable
internal fun BorrowCaptureScreen(
    mediaSessionService: MediaSessionService,
    modelSetupService: ModelSetupService,
) {
    val stateHolder = remember(mediaSessionService, modelSetupService) {
        CaptureUiStateHolder(
            mediaSessionService = mediaSessionService,
            initiallyModelReady = modelSetupService.state.value.phase == ModelSetupPhase.READY,
        )
    }
    val uiState by stateHolder.uiState.collectAsState()
    val setupState by modelSetupService.state.collectAsState()
    val scope = rememberCoroutineScope()
    val modelReady = setupState.phase == ModelSetupPhase.READY
    val modelStatus = modelSetupStatus(setupState)
    val modelFailureDetail = modelSetupFailureDetail(setupState)

    LaunchedEffect(stateHolder, modelSetupService) {
        launch { stateHolder.observeMediaSession() }
        launch {
            modelSetupService.state.collect { stateHolder.setModelReady(it.phase == ModelSetupPhase.READY) }
        }
        modelSetupService.prepare()
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
                isEnabled = modelReady,
                elapsedSeconds = uiState.elapsedSeconds,
                statusLabel = modelStatus,
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
                if (modelReady) {
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
                } else if (setupState.phase == ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK) {
                    Button(onClick = { scope.launch { modelSetupService.downloadOverMobileData() } }) {
                        Text(stringResource(Res.string.capture_download_over_mobile_data))
                    }
                }
                if (modelFailureDetail != null) {
                    Text(
                        text = modelFailureDetail,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    isEnabled: Boolean,
    elapsedSeconds: Int,
    statusLabel: String?,
    onToggleRecording: () -> Unit,
    onStartHoldRecording: () -> Unit,
    onStopHoldRecording: () -> Unit,
) {
    val latestIsRecording = rememberUpdatedState(isRecording)
    val latestIsEnabled = rememberUpdatedState(isEnabled)
    val latestOnToggle = rememberUpdatedState(onToggleRecording)
    val latestOnHoldStart = rememberUpdatedState(onStartHoldRecording)
    val latestOnHoldStop = rememberUpdatedState(onStopHoldRecording)
    val defaultDescription = stringResource(
        if (isRecording) Res.string.capture_stop_recording_description
        else Res.string.capture_start_recording_description,
    )
    val microphoneDescription = if (isEnabled) defaultDescription
    else statusLabel ?: stringResource(Res.string.capture_models_unavailable)
    val infiniteTransition = rememberInfiniteTransition(label = "mic-pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (!isEnabled) 1f else if (isRecording) {
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
        targetValue = when {
            !isEnabled -> MaterialTheme.colorScheme.surfaceVariant
            isRecording -> MaterialTheme.colorScheme.error
            else -> Color.White
        },
        animationSpec = tween(CaptureUiStateHolder.COLOR_TRANSITION_MILLIS),
        label = "mic-color",
    )
    val buttonContentColor = when {
        !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        isRecording -> Color.White
        else -> MaterialTheme.colorScheme.primary
    }
    val pulseColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val buttonBorderColor = if (isRecording) {
        MaterialTheme.colorScheme.error.copy(alpha = CaptureUiStateHolder.RECORDING_BORDER_ALPHA)
    } else if (!isEnabled) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
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
            if (isEnabled) {
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
        }
        Surface(
            modifier = Modifier.size(buttonSize * pulse)
                .padding(CaptureUiStateHolder.BUTTON_CONTENT_INSET)
                .semantics {
                    role = Role.Button
                    contentDescription = microphoneDescription
                    if (isEnabled) {
                        onClick {
                            latestOnToggle.value()
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .pointerInput(isEnabled) {
                    if (!isEnabled) return@pointerInput
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
                            if (latestIsEnabled.value && !latestIsRecording.value) {
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
                    text = statusLabel ?: stringResource(
                        if (isRecording) Res.string.capture_recording_label else Res.string.capture_tap_or_hold,
                    ),
                    color = buttonContentColor,
                    style = if (isEnabled) MaterialTheme.typography.labelLarge
                    else MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = CaptureUiStateHolder.BUTTON_CONTENT_FONT_SPACING,
                    textAlign = TextAlign.Center,
                    maxLines = if (isEnabled) 1 else 3,
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

@Composable
private fun modelSetupStatus(state: ModelSetupState): String? = when (state.phase) {
    ModelSetupPhase.NOT_STARTED,
    ModelSetupPhase.CHOOSING_MODEL -> stringResource(Res.string.capture_model_choosing)
    ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK ->
        stringResource(Res.string.capture_model_waiting_for_unmetered_network)
    ModelSetupPhase.WAITING_FOR_NETWORK -> stringResource(Res.string.capture_model_waiting_for_network)
    ModelSetupPhase.WAITING_FOR_RETRY -> stringResource(Res.string.capture_model_waiting_for_retry)
    ModelSetupPhase.DOWNLOADING_VOICE -> {
        if (state.bytesTotal > 0L) {
            stringResource(
                Res.string.capture_model_progress_transcription,
                formatMegabytes(state.bytesDownloaded),
                formatMegabytes(state.bytesTotal),
            )
        } else {
            stringResource(Res.string.capture_model_downloading_transcription)
        }
    }
    ModelSetupPhase.LOADING_VOICE -> stringResource(Res.string.capture_model_loading_transcription)
    ModelSetupPhase.DOWNLOADING_LLM -> {
        if (state.bytesTotal > 0L) {
            stringResource(
                Res.string.capture_model_progress_summarization,
                formatMegabytes(state.bytesDownloaded),
                formatMegabytes(state.bytesTotal),
            )
        } else {
            stringResource(Res.string.capture_model_downloading_summarization)
        }
    }
    ModelSetupPhase.READY -> null
    ModelSetupPhase.FAILED -> stringResource(
        when (state.failureReason) {
            ModelSetupFailureReason.NETWORK -> Res.string.capture_model_failed_network
            ModelSetupFailureReason.STORAGE -> Res.string.capture_model_failed_storage
            ModelSetupFailureReason.MODEL,
            null -> Res.string.capture_model_failed_model
        },
    )
}

@Composable
private fun modelSetupFailureDetail(state: ModelSetupState): String? {
    if (state.phase != ModelSetupPhase.FAILED) return null
    return stringResource(
        when (state.failureReason) {
            ModelSetupFailureReason.NETWORK -> Res.string.capture_model_failed_network_detail
            ModelSetupFailureReason.STORAGE -> Res.string.capture_model_failed_storage_detail
            ModelSetupFailureReason.MODEL,
            null -> Res.string.capture_model_failed_model_detail
        },
    )
}

private fun formatMegabytes(bytes: Long): String {
    require(bytes >= 0L) { "A byte count cannot be negative." }
    val digits = (bytes / BYTES_PER_MEGABYTE).toString()
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

private const val BYTES_PER_MEGABYTE = 1_000_000L
