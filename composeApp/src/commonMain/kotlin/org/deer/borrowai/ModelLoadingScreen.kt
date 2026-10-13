package org.deer.borrowai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupPhase
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.model_app_name
import borrowai.composeapp.generated.resources.model_downloading_summarization
import borrowai.composeapp.generated.resources.model_downloading_transcription
import borrowai.composeapp.generated.resources.model_loading_summarization
import borrowai.composeapp.generated.resources.model_loading_transcription
import borrowai.composeapp.generated.resources.model_ready
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ModelLoadingScreen(modelSetupService: ModelSetupService) {
    val stateHolder = remember(modelSetupService) {
        ModelLoadingUiStateHolder(modelSetupService)
    }
    val uiState by stateHolder.uiState.collectAsState()
    LaunchedEffect(stateHolder) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            stateHolder.observeState()
        }
        stateHolder.prepare()
    }
    val setupSteps = listOf(
        stringResource(Res.string.model_downloading_transcription),
        stringResource(Res.string.model_loading_transcription),
        stringResource(Res.string.model_downloading_summarization),
        stringResource(Res.string.model_loading_summarization),
        stringResource(Res.string.model_ready),
    )
    val animatedProgress by animateFloatAsState(
        targetValue = uiState.progress.coerceIn(0f, 1f),
        animationSpec = tween(
            ModelLoadingUiStateHolder.PROGRESS_ANIMATION_MILLIS,
            easing = LinearEasing,
        ),
        label = "model-setup-progress",
    )

    Box(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = ModelLoadingUiStateHolder.SCREEN_HORIZONTAL_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ModelLoadingUiStateHolder.CONTENT_SPACING),
        ) {
            Text(
                stringResource(Res.string.model_app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val panelShape = RoundedCornerShape(ModelLoadingUiStateHolder.PANEL_CORNER_RADIUS)
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(panelShape)
                    .drawBehind {
                        drawRoundRect(
                            color = Color.White.copy(alpha = ModelLoadingUiStateHolder.PANEL_FILL_ALPHA),
                            cornerRadius = CornerRadius(ModelLoadingUiStateHolder.PANEL_CORNER_RADIUS.toPx()),
                        )
                    }
                    .border(
                        ModelLoadingUiStateHolder.PANEL_BORDER_WIDTH,
                        Color.White.copy(alpha = ModelLoadingUiStateHolder.PANEL_BORDER_ALPHA),
                        panelShape,
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = ModelLoadingUiStateHolder.PANEL_HORIZONTAL_PADDING,
                        vertical = ModelLoadingUiStateHolder.PANEL_VERTICAL_PADDING,
                    ),
                ) {
                    ModelBullet(
                        name = setupSteps.first(),
                        completed = animatedProgress >= 1f / ModelLoadingUiStateHolder.SETUP_STEP_COUNT,
                        active = isCurrentStep(uiState.phase, 0),
                    )
                    repeat(ModelLoadingUiStateHolder.SETUP_STEP_COUNT) { segment ->
                        ProgressLineSegment(
                            progress = (animatedProgress * ModelLoadingUiStateHolder.SETUP_STEP_COUNT - segment)
                                .coerceIn(0f, 1f),
                        )
                        val nextStep = segment + 1
                        val completed = if (nextStep < ModelLoadingUiStateHolder.SETUP_STEP_COUNT) {
                            animatedProgress >= (nextStep + 1).toFloat() /
                                ModelLoadingUiStateHolder.SETUP_STEP_COUNT
                        } else {
                            animatedProgress >= 1f
                        }
                        ModelBullet(
                            name = setupSteps[nextStep],
                            completed = completed,
                            active = isCurrentStep(uiState.phase, nextStep),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressLineSegment(progress: Float) {
    val trackColor = MaterialTheme.colorScheme.outline
    val progressColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier.width(ModelLoadingUiStateHolder.BULLET_SIZE)
            .height(ModelLoadingUiStateHolder.CONNECTOR_HEIGHT),
    ) {
        val x = size.width / 2
        val strokeWidth = ModelLoadingUiStateHolder.CONNECTOR_WIDTH.toPx()
        drawLine(
            color = trackColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = progressColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height * progress),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ModelBullet(
    name: String,
    completed: Boolean,
    active: Boolean,
) {
    val completionProgress by animateFloatAsState(
        targetValue = if (completed) 1f else 0f,
        animationSpec = tween(ModelLoadingUiStateHolder.BULLET_TRANSITION_MILLIS),
        label = "model-bullet-complete",
    )
    val activeProgress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(ModelLoadingUiStateHolder.BULLET_TRANSITION_MILLIS),
        label = "model-bullet-active",
    )
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
    val outlineColor = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier.fillMaxWidth().height(ModelLoadingUiStateHolder.BULLET_SIZE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ModelLoadingUiStateHolder.BULLET_SIZE).drawBehind {
                val emphasis = maxOf(activeProgress, completionProgress)
                if (activeProgress > 0f) {
                    drawCircle(
                        color = primaryContainerColor.copy(alpha = activeProgress),
                        radius = size.minDimension / 2 +
                            ModelLoadingUiStateHolder.ACTIVE_HALO_EXTENT.toPx() * activeProgress,
                    )
                }
                drawCircle(
                    color = Color.White.copy(
                        alpha = ModelLoadingUiStateHolder.BULLET_FILL_ALPHA * (1f - completionProgress),
                    ),
                    radius = size.minDimension / 2 - ModelLoadingUiStateHolder.BULLET_STROKE.toPx() / 2,
                )
                drawCircle(
                    color = primaryColor.copy(alpha = completionProgress),
                    radius = size.minDimension / 2 - ModelLoadingUiStateHolder.BULLET_STROKE.toPx() / 2,
                )
                drawCircle(
                    color = outlineColor.copy(alpha = 1f - emphasis),
                    style = Stroke(width = ModelLoadingUiStateHolder.BULLET_STROKE.toPx()),
                )
                drawCircle(
                    color = primaryColor.copy(alpha = emphasis),
                    style = Stroke(width = ModelLoadingUiStateHolder.BULLET_STROKE.toPx()),
                )
                drawCircle(
                    color = primaryColor.copy(alpha = activeProgress),
                    radius = size.minDimension *
                        ModelLoadingUiStateHolder.BULLET_CENTER_DOT_RADIUS_FRACTION,
                )
                drawLine(
                    color = Color.White.copy(alpha = completionProgress),
                    start = Offset(
                        size.width * ModelLoadingUiStateHolder.CHECK_START_X_FRACTION,
                        size.height * ModelLoadingUiStateHolder.CHECK_START_Y_FRACTION,
                    ),
                    end = Offset(
                        size.width * ModelLoadingUiStateHolder.CHECK_MIDDLE_X_FRACTION,
                        size.height * ModelLoadingUiStateHolder.CHECK_MIDDLE_Y_FRACTION,
                    ),
                    strokeWidth = ModelLoadingUiStateHolder.CHECK_STROKE.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White.copy(alpha = completionProgress),
                    start = Offset(
                        size.width * ModelLoadingUiStateHolder.CHECK_MIDDLE_X_FRACTION,
                        size.height * ModelLoadingUiStateHolder.CHECK_MIDDLE_Y_FRACTION,
                    ),
                    end = Offset(
                        size.width * ModelLoadingUiStateHolder.CHECK_END_X_FRACTION,
                        size.height * ModelLoadingUiStateHolder.CHECK_END_Y_FRACTION,
                    ),
                    strokeWidth = ModelLoadingUiStateHolder.CHECK_STROKE.toPx(),
                    cap = StrokeCap.Round,
                )
            },
        )
        Text(
            text = name,
            modifier = Modifier.padding(start = ModelLoadingUiStateHolder.BULLET_LABEL_PADDING),
            color = if (active || completed) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active || completed) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun isCurrentStep(phase: ModelSetupPhase, step: Int): Boolean =
    when (phase) {
        ModelSetupPhase.NOT_STARTED,
        ModelSetupPhase.DOWNLOADING_TRANSCRIPTION -> step == 0
        ModelSetupPhase.LOADING_TRANSCRIPTION -> step == 1
        ModelSetupPhase.DOWNLOADING_SUMMARIZATION -> step == 2
        ModelSetupPhase.LOADING_SUMMARIZATION -> step == 3
        ModelSetupPhase.READY -> false
    }
