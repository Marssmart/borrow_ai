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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp

private val ModelSetupSteps = listOf(
    "Downloading voice-to-text model",
    "Loading voice-to-text model",
    "Downloading summarization model",
    "Loading summarization model",
    "Ready",
)

@Composable
internal fun ModelLoadingScreen(progress: Float) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(MODEL_PROGRESS_ANIMATION_MILLIS, easing = LinearEasing),
        label = "model-setup-progress",
    )

    Box(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(44.dp),
        ) {
            Text(
                "BorrowAI",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val panelShape = RoundedCornerShape(24.dp)
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(panelShape)
                    .drawBehind {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.46f),
                            cornerRadius = CornerRadius(24.dp.toPx()),
                        )
                    }
                    .border(1.dp, Color.White.copy(alpha = 0.94f), panelShape),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 17.dp),
                ) {
                    ModelBullet(
                        name = ModelSetupSteps.first(),
                        completed = animatedProgress >= 1f / MODEL_SETUP_STEP_COUNT,
                        active = isCurrentStep(animatedProgress, 0),
                    )
                    repeat(MODEL_SETUP_STEP_COUNT) { segment ->
                        ProgressLineSegment(
                            progress = (animatedProgress * MODEL_SETUP_STEP_COUNT - segment)
                                .coerceIn(0f, 1f),
                        )
                        val nextStep = segment + 1
                        val completed = if (nextStep < MODEL_SETUP_STEP_COUNT) {
                            animatedProgress >= (nextStep + 1).toFloat() / MODEL_SETUP_STEP_COUNT
                        } else {
                            animatedProgress >= 1f
                        }
                        ModelBullet(
                            name = ModelSetupSteps[nextStep],
                            completed = completed,
                            active = isCurrentStep(animatedProgress, nextStep),
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
        modifier = Modifier.width(MODEL_BULLET_SIZE).height(MODEL_CONNECTOR_HEIGHT),
    ) {
        val x = size.width / 2
        val strokeWidth = MODEL_CONNECTOR_WIDTH.toPx()
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
        animationSpec = tween(MODEL_BULLET_TRANSITION_MILLIS),
        label = "model-bullet-complete",
    )
    val activeProgress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(MODEL_BULLET_TRANSITION_MILLIS),
        label = "model-bullet-active",
    )
    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer
    val outlineColor = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier.fillMaxWidth().height(MODEL_BULLET_SIZE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(MODEL_BULLET_SIZE).drawBehind {
                val emphasis = maxOf(activeProgress, completionProgress)
                if (activeProgress > 0f) {
                    drawCircle(
                        color = primaryContainerColor.copy(alpha = activeProgress),
                        radius = size.minDimension / 2 +
                            MODEL_ACTIVE_HALO_EXTENT.toPx() * activeProgress,
                    )
                }
                drawCircle(
                    color = Color.White.copy(
                        alpha = MODEL_BULLET_FILL_ALPHA * (1f - completionProgress),
                    ),
                    radius = size.minDimension / 2 - MODEL_BULLET_STROKE.toPx() / 2,
                )
                drawCircle(
                    color = primaryColor.copy(alpha = completionProgress),
                    radius = size.minDimension / 2 - MODEL_BULLET_STROKE.toPx() / 2,
                )
                drawCircle(
                    color = outlineColor.copy(alpha = 1f - emphasis),
                    style = Stroke(width = MODEL_BULLET_STROKE.toPx()),
                )
                drawCircle(
                    color = primaryColor.copy(alpha = emphasis),
                    style = Stroke(width = MODEL_BULLET_STROKE.toPx()),
                )
                drawCircle(
                    color = primaryColor.copy(alpha = activeProgress),
                    radius = size.minDimension * 0.16f,
                )
                drawLine(
                    color = Color.White.copy(alpha = completionProgress),
                    start = Offset(size.width * 0.29f, size.height * 0.52f),
                    end = Offset(size.width * 0.44f, size.height * 0.67f),
                    strokeWidth = MODEL_CHECK_STROKE.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White.copy(alpha = completionProgress),
                    start = Offset(size.width * 0.44f, size.height * 0.67f),
                    end = Offset(size.width * 0.73f, size.height * 0.34f),
                    strokeWidth = MODEL_CHECK_STROKE.toPx(),
                    cap = StrokeCap.Round,
                )
            },
        )
        Text(
            text = name,
            modifier = Modifier.padding(start = 14.dp),
            color = if (active || completed) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active || completed) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun isCurrentStep(progress: Float, step: Int): Boolean =
    step < MODEL_SETUP_STEP_COUNT &&
        progress >= step.toFloat() / MODEL_SETUP_STEP_COUNT &&
        progress < (step + 1).toFloat() / MODEL_SETUP_STEP_COUNT

private const val MODEL_PROGRESS_ANIMATION_MILLIS = 100
private const val MODEL_BULLET_TRANSITION_MILLIS = 220
private const val MODEL_SETUP_STEP_COUNT = 4
private val MODEL_BULLET_SIZE = 22.dp
private val MODEL_CONNECTOR_HEIGHT = 42.dp
private val MODEL_CONNECTOR_WIDTH = 2.dp
private val MODEL_BULLET_STROKE = 2.dp
private val MODEL_CHECK_STROKE = 1.6.dp
private val MODEL_ACTIVE_HALO_EXTENT = 4.dp
private const val MODEL_BULLET_FILL_ALPHA = 0.88f
