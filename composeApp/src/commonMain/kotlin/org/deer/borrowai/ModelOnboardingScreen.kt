package org.deer.borrowai

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ModelOnboardingScreen(
    selectedSpeechModel: String?,
    selectedLanguageModel: String?,
    downloadProgress: Map<String, Float>,
    canContinue: Boolean,
    onSelectSpeechModel: (String) -> Unit,
    onSelectLanguageModel: (String) -> Unit,
    onDownload: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark()
                MockModeBadge()
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Choose your on-device models",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick one voice model and one writing model. This demo simulates model details and downloads.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))

            ModelSection(
                title = "VOICE TO TEXT",
                subtitle = "Turn speech into a transcript",
                models = MockModels.speech,
                selectedModel = selectedSpeechModel,
                downloadProgress = downloadProgress,
                onSelect = onSelectSpeechModel,
                onDownload = onDownload,
            )
            Spacer(Modifier.height(20.dp))
            ModelSection(
                title = "ON-DEVICE LLM",
                subtitle = "Create a title and summary",
                models = MockModels.language,
                selectedModel = selectedLanguageModel,
                downloadProgress = downloadProgress,
                onSelect = onSelectLanguageModel,
                onDownload = onDownload,
            )
            Spacer(Modifier.height(20.dp))
        }

        SetupFooter(
            speechReady = selectedSpeechModel?.let { downloadProgress[it] == 1f } == true,
            languageReady = selectedLanguageModel?.let { downloadProgress[it] == 1f } == true,
            canContinue = canContinue,
            onContinue = onContinue,
        )
    }
}

@Composable
private fun ModelSection(
    title: String,
    subtitle: String,
    models: List<MockModel>,
    selectedModel: String?,
    downloadProgress: Map<String, Float>,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        models.forEach { model ->
            ModelOptionCard(
                model = model,
                selected = selectedModel == model.id,
                progress = downloadProgress[model.id],
                onSelect = { onSelect(model.id) },
                onDownload = { onSelect(model.id); onDownload(model.id) },
            )
        }
    }
}

@Composable
private fun ModelOptionCard(
    model: MockModel,
    selected: Boolean,
    progress: Float?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    val downloaded = progress != null && progress >= 1f
    val downloading = progress != null && !downloaded
    val tierColor = when (model.tier) {
        ModelTier.LowMemory -> Color(0xFF526A91)
        ModelTier.Balanced -> MaterialTheme.colorScheme.primary
        ModelTier.HighPerformance -> Color(0xFF96611B)
    }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(180)).clickable(onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFF0F7F2) else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected,
                    onClick = onSelect,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                TierBadge(model.tier.label, tierColor)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${model.size}  ·  ${model.memory}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(
                    onClick = onDownload,
                    enabled = !downloading && !downloaded,
                    modifier = Modifier.widthIn(min = 96.dp, max = 104.dp).height(36.dp),
                ) {
                    Text(
                        when {
                            downloaded -> "Ready"
                            downloading -> "In progress"
                            else -> "Download"
                        },
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }

            if (downloading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    )
                    Text(
                        "Mock download · ${((progress ?: 0f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TierBadge(label: String, color: Color) {
    Text(
        text = label,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.11f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        maxLines = 1,
    )
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("B", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            "BorrowAI",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MockModeBadge() {
    Text(
        "DEMO · MOCK DATA",
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SetupFooter(
    speechReady: Boolean,
    languageReady: Boolean,
    canContinue: Boolean,
    onContinue: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DownloadStatus("Voice model", speechReady)
                DownloadStatus("Writing model", languageReady)
            }
            Button(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Continue to recording", fontWeight = FontWeight.SemiBold)
            }
            if (!canContinue) {
                Text(
                    "Select and download one model from each section to continue.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DownloadStatus(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape).background(
                if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (ready) "$label ready" else "$label pending",
            style = MaterialTheme.typography.labelSmall,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
