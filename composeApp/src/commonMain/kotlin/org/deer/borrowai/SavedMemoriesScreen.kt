package org.deer.borrowai

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private enum class MockAudioTrack {
    Original,
    Summary,
}

private data class ActiveMockPlayback(
    val memoryId: String,
    val track: MockAudioTrack,
)

@Composable
internal fun SavedMemoriesScreen(
    onReturnToCapture: () -> Unit,
    onDetailVisibilityChanged: (Boolean) -> Unit,
) {
    var selectedMemoryId by remember { mutableStateOf<String?>(null) }
    var activePlayback by remember { mutableStateOf<ActiveMockPlayback?>(null) }
    var detailSwipeEnabled by remember { mutableStateOf(false) }
    val memoryPagerState = rememberPagerState(
        pageCount = { MEMORY_PAGE_COUNT },
    )
    val memoriesScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val selectedMemory = MockMemories.firstOrNull { it.id == selectedMemoryId }

    fun togglePlayback(memoryId: String, track: MockAudioTrack) {
        activePlayback = if (activePlayback == ActiveMockPlayback(memoryId, track)) {
            null
        } else {
            ActiveMockPlayback(memoryId, track)
        }
    }

    LaunchedEffect(selectedMemoryId, detailSwipeEnabled) {
        if (selectedMemoryId != null && detailSwipeEnabled) {
            memoryPagerState.animateScrollToPage(MEMORY_DETAIL_PAGE)
        }
    }

    LaunchedEffect(memoryPagerState) {
        var hasOpenedDetail = false
        snapshotFlow { memoryPagerState.settledPage }.collect { page ->
            if (page == MEMORY_DETAIL_PAGE) {
                hasOpenedDetail = true
                onDetailVisibilityChanged(true)
            } else if (hasOpenedDetail) {
                hasOpenedDetail = false
                detailSwipeEnabled = false
                onDetailVisibilityChanged(false)
            }
        }
    }

    HorizontalPager(
        state = memoryPagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = detailSwipeEnabled,
    ) { page ->
        when (page) {
            MEMORY_LIST_PAGE -> MemoryListScreen(
                scrollState = memoriesScrollState,
                playingMemoryId = activePlayback?.memoryId,
                playingTrack = activePlayback?.track,
                onOpenMemory = {
                    selectedMemoryId = it
                    detailSwipeEnabled = true
                    onDetailVisibilityChanged(true)
                },
                onPlayOriginal = { togglePlayback(it, MockAudioTrack.Original) },
                onPlaySummary = { togglePlayback(it, MockAudioTrack.Summary) },
                onReturnToCapture = onReturnToCapture,
            )

            MEMORY_DETAIL_PAGE -> selectedMemory?.let { memory ->
                MemoryDetailScreen(
                    memory = memory,
                    playingTrack = activePlayback?.takeIf { it.memoryId == memory.id }?.track,
                    onBack = {
                        scope.launch {
                            memoryPagerState.animateScrollToPage(MEMORY_LIST_PAGE)
                            selectedMemoryId = null
                            detailSwipeEnabled = false
                            onDetailVisibilityChanged(false)
                        }
                    },
                    onPlayOriginal = { togglePlayback(memory.id, MockAudioTrack.Original) },
                    onPlaySummary = { togglePlayback(memory.id, MockAudioTrack.Summary) },
                    onReturnToCapture = {
                        scope.launch {
                            memoryPagerState.animateScrollToPage(MEMORY_LIST_PAGE)
                            selectedMemoryId = null
                            detailSwipeEnabled = false
                            onDetailVisibilityChanged(false)
                            onReturnToCapture()
                        }
                    },
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun MemoryListScreen(
    scrollState: ScrollState,
    playingMemoryId: String?,
    playingTrack: MockAudioTrack?,
    onOpenMemory: (String) -> Unit,
    onPlayOriginal: (String) -> Unit,
    onPlaySummary: (String) -> Unit,
    onReturnToCapture: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onReturnToCapture) {
                Text("Capture")
            }
        }

        Text(
            "Saved memories",
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 14.dp, bottom = 18.dp),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MockMemories.forEach { memory ->
                MemoryCard(
                    memory = memory,
                    playingTrack = playingTrack.takeIf { playingMemoryId == memory.id },
                    onOpen = { onOpenMemory(memory.id) },
                    onPlayOriginal = { onPlayOriginal(memory.id) },
                    onPlaySummary = { onPlaySummary(memory.id) },
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
    playingTrack: MockAudioTrack?,
    onOpen: () -> Unit,
    onPlayOriginal: () -> Unit,
    onPlaySummary: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onOpen)
            .animateContentSize(tween(220)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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

            MemoryTextSection(
                label = "SUMMARY",
                text = memory.summary.preview(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            MemoryTextSection(
                label = "ORIGINAL TRANSCRIPTION",
                text = memory.originalText.preview(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AudioPlaybackButtons(
                playingTrack = playingTrack,
                onPlayOriginal = onPlayOriginal,
                onPlaySummary = onPlaySummary,
            )
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
        }
    }
}

@Composable
private fun MemoryDetailScreen(
    memory: MockMemory,
    playingTrack: MockAudioTrack?,
    onBack: () -> Unit,
    onPlayOriginal: () -> Unit,
    onPlaySummary: () -> Unit,
    onReturnToCapture: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("← Memories")
            }
            TextButton(onClick = onReturnToCapture) {
                Text("Capture")
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                memory.dateLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                memory.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            AudioPlaybackButtons(
                playingTrack = playingTrack,
                onPlayOriginal = onPlayOriginal,
                onPlaySummary = onPlaySummary,
            )
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            MemoryTextSection(
                label = "SUMMARY",
                text = memory.summary,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            MemoryTextSection(
                label = "ORIGINAL TRANSCRIPTION",
                text = memory.originalText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryTextSection(
    label: String,
    text: String,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun AudioPlaybackButtons(
    playingTrack: MockAudioTrack?,
    onPlayOriginal: () -> Unit,
    onPlaySummary: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onPlayOriginal) {
            Text(if (playingTrack == MockAudioTrack.Original) "Ⅱ  Pause original" else "▶  Play original")
        }
        TextButton(onClick = onPlaySummary) {
            Text(if (playingTrack == MockAudioTrack.Summary) "Ⅱ  Pause summary" else "▶  Play summary")
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

private fun String.preview(): String =
    if (length > MEMORY_PREVIEW_CHAR_LIMIT) take(MEMORY_PREVIEW_CHAR_LIMIT).trimEnd() + "..." else this

private const val MEMORY_PREVIEW_CHAR_LIMIT = 240
private const val MEMORY_PAGE_COUNT = 2
private const val MEMORY_LIST_PAGE = 0
private const val MEMORY_DETAIL_PAGE = 1
