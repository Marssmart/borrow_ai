package org.deer.borrowai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
        animationSpec = tween(MODEL_PROGRESS_ANIMATION_MILLIS),
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
            Column(modifier = Modifier.fillMaxWidth()) {
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
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier.fillMaxWidth().height(MODEL_BULLET_SIZE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(MODEL_BULLET_SIZE)
                .clip(CircleShape)
                .drawBehind {
                    if (completed) {
                        drawCircle(color = primaryColor)
                    } else {
                        drawCircle(
                            color = if (active) primaryColor else outlineColor,
                            style = Stroke(width = MODEL_BULLET_STROKE.toPx()),
                        )
                        if (active) {
                            drawCircle(
                                color = primaryColor,
                                radius = size.minDimension * 0.16f,
                            )
                        }
                    }
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

private const val MODEL_PROGRESS_ANIMATION_MILLIS = 120
private const val MODEL_SETUP_STEP_COUNT = 4
private val MODEL_BULLET_SIZE = 22.dp
private val MODEL_CONNECTOR_HEIGHT = 42.dp
private val MODEL_CONNECTOR_WIDTH = 2.dp
private val MODEL_BULLET_STROKE = 2.dp
