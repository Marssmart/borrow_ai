package org.deer.borrowai

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class MockAudioTrack {
    Original,
    Summary,
}

private data class ActiveMockPlayback(
    val memoryId: String,
    val track: MockAudioTrack,
)

@Composable
internal fun SavedMemoriesScreen(onReturnToCapture: () -> Unit) {
    var expandedMemoryId by remember { mutableStateOf<String?>(null) }
    var activePlayback by remember { mutableStateOf<ActiveMockPlayback?>(null) }

    fun togglePlayback(memoryId: String, track: MockAudioTrack) {
        activePlayback = if (activePlayback == ActiveMockPlayback(memoryId, track)) {
            null
        } else {
            ActiveMockPlayback(memoryId, track)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(rememberHorizontalPageSwipe(onSwipeRight = onReturnToCapture)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp),
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
            }
            TextButton(onClick = onReturnToCapture) {
                Text("Capture")
            }
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 14.dp, bottom = 14.dp)) {
            Text(
                "Saved memories",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${MockMemories.size} demo memories · newest first · playback is simulated",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MockMemories.forEach { memory ->
                MemoryCard(
                    memory = memory,
                    expanded = expandedMemoryId == memory.id,
                    playingTrack = activePlayback?.takeIf { it.memoryId == memory.id }?.track,
                    onToggleExpanded = {
                        expandedMemoryId = if (expandedMemoryId == memory.id) null else memory.id
                    },
                    onPlayOriginal = { togglePlayback(memory.id, MockAudioTrack.Original) },
                    onPlaySummary = { togglePlayback(memory.id, MockAudioTrack.Summary) },
                )
            }
        }

        Text(
            "Swipe right to return to recording",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun MemoryCard(
    memory: MockMemory,
    expanded: Boolean,
    playingTrack: MockAudioTrack?,
    onToggleExpanded: () -> Unit,
    onPlayOriginal: () -> Unit,
    onPlaySummary: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(220)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onPlayOriginal,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AudioGlyph(playing = playingTrack == MockAudioTrack.Original)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                memory.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                memory.dateLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                TextButton(onClick = onToggleExpanded, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (expanded) "Close" else "Open")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onPlaySummary) {
                    Text(if (playingTrack == MockAudioTrack.Summary) "Ⅱ  Pause summary" else "▶  Play summary")
                }
            }

            if (playingTrack != null) {
                Text(
                    if (playingTrack == MockAudioTrack.Original) {
                        "Playing original audio · demo"
                    } else {
                        "Playing summary audio · demo"
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(
                    "SUMMARY",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                )
                Text(
                    memory.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "ORIGINAL TRANSCRIPTION",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                )
                Text(
                    memory.originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AudioGlyph(playing: Boolean) {
    Box(
        modifier = Modifier.size(34.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (playing) "Ⅱ" else "▶",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
