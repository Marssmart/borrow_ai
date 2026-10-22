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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackTarget
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_audio_not_playing
import borrowai.composeapp.generated.resources.memory_audio_playing
import borrowai.composeapp.generated.resources.memory_back_to_memories
import borrowai.composeapp.generated.resources.memory_capture
import borrowai.composeapp.generated.resources.memory_dismiss
import borrowai.composeapp.generated.resources.memory_open_details
import borrowai.composeapp.generated.resources.memory_original_transcription
import borrowai.composeapp.generated.resources.memory_play_summary
import borrowai.composeapp.generated.resources.memory_playback_confirmation_cancel
import borrowai.composeapp.generated.resources.memory_playback_confirmation_confirm
import borrowai.composeapp.generated.resources.memory_playback_confirmation_message
import borrowai.composeapp.generated.resources.memory_playback_confirmation_title
import borrowai.composeapp.generated.resources.memory_playback_error_title
import borrowai.composeapp.generated.resources.memory_playback_memory_missing_message
import borrowai.composeapp.generated.resources.memory_playback_recording_unavailable_message
import borrowai.composeapp.generated.resources.memory_playback_request_failed_message
import borrowai.composeapp.generated.resources.memory_play_recording
import borrowai.composeapp.generated.resources.memory_recording_audio_playing
import borrowai.composeapp.generated.resources.memory_return_to_recording
import borrowai.composeapp.generated.resources.memory_saved_memories_title
import borrowai.composeapp.generated.resources.memory_stop_recording
import borrowai.composeapp.generated.resources.memory_stop_summary
import borrowai.composeapp.generated.resources.memory_transcription_failed
import borrowai.composeapp.generated.resources.memory_waiting_transcription
import borrowai.composeapp.generated.resources.memory_summary
import borrowai.composeapp.generated.resources.memory_summary_audio_playing
import borrowai.composeapp.generated.resources.memory_summary_model_missing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SavedMemoriesScreen(
    services: AppServices,
    onReturnToCapture: () -> Unit,
    onDetailVisibilityChanged: (Boolean) -> Unit,
) {
    val memoryPagerState = rememberPagerState(
        pageCount = { SavedMemoriesStateHolder.MEMORY_PAGE_COUNT },
    )
    val memoriesScrollState = rememberScrollState()
    val detailScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stateHolder = remember(
        services,
        memoryPagerState,
        memoriesScrollState,
        detailScrollState,
        scope,
    ) {
        SavedMemoriesStateHolder(
            memoryService = services.memories,
            mediaSessionService = services.mediaSession,
            pagerState = memoryPagerState,
            memoriesScrollState = memoriesScrollState,
            detailScrollState = detailScrollState,
            scope = scope,
            summaryModelMissing = services.summaryModelMissing,
        )
    }
    val uiState by stateHolder.uiState.collectAsState()
    val currentOnDetailVisibilityChanged by rememberUpdatedState(onDetailVisibilityChanged)
    val currentOnReturnToCapture by rememberUpdatedState(onReturnToCapture)
    val selectedMemory = uiState.memories.firstOrNull { it.id == uiState.selectedMemoryId }

    androidx.compose.runtime.LaunchedEffect(stateHolder.pagerState) {
        snapshotFlow { stateHolder.pagerState.settledPage }.collect(stateHolder::onPagerPageSettled)
    }
    androidx.compose.runtime.LaunchedEffect(stateHolder) {
        var hasReportedDetailVisible = false
        stateHolder.uiState.map { it.hasOpenedDetail }.distinctUntilChanged().collect { isVisible ->
            if (isVisible || hasReportedDetailVisible) {
                currentOnDetailVisibilityChanged(isVisible)
                hasReportedDetailVisible = isVisible
            }
        }
    }

    HorizontalPager(
        state = stateHolder.pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = uiState.detailSwipeEnabled,
    ) { page ->
        when (page) {
            SavedMemoriesStateHolder.MEMORY_LIST_PAGE -> MemoryListScreen(
                memories = uiState.memories,
                summaryModelMissing = uiState.summaryModelMissing,
                scrollState = stateHolder.memoriesScrollState,
                playingTarget = (uiState.mediaSessionState as? MediaSessionState.Playing)?.target,
                onOpenMemory = stateHolder::openMemory,
                onPlaySummary = { stateHolder.togglePlayback(it, PlaybackContent.SUMMARY) },
                onPlayRecording = { stateHolder.togglePlayback(it, PlaybackContent.RECORDING) },
            )

            SavedMemoriesStateHolder.MEMORY_DETAIL_PAGE -> selectedMemory?.let { memory ->
                MemoryDetailScreen(
                    memory = memory,
                    scrollState = stateHolder.detailScrollState,
                    playingContent = (uiState.mediaSessionState as? MediaSessionState.Playing)
                        ?.takeIf { it.target.memoryId == memory.id }
                        ?.target
                        ?.content,
                    onBack = stateHolder::backToList,
                    onPlaySummary = {
                        stateHolder.togglePlayback(memory.id, PlaybackContent.SUMMARY)
                    },
                    onPlayRecording = {
                        stateHolder.togglePlayback(memory.id, PlaybackContent.RECORDING)
                    },
                    onReturnToCapture = {
                        stateHolder.returnToCapture(currentOnReturnToCapture)
                    },
                )
            }

            else -> Unit
        }
    }

    val pendingPlayback = (uiState.mediaSessionState as? MediaSessionState.Recording)
        ?.pendingPlayback
    if (pendingPlayback != null) {
        AlertDialog(
            onDismissRequest = stateHolder::cancelPendingPlayback,
            title = { Text(stringResource(Res.string.memory_playback_confirmation_title)) },
            text = { Text(stringResource(Res.string.memory_playback_confirmation_message)) },
            confirmButton = {
                TextButton(onClick = stateHolder::confirmPendingPlayback) {
                    Text(stringResource(Res.string.memory_playback_confirmation_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = stateHolder::cancelPendingPlayback) {
                    Text(stringResource(Res.string.memory_playback_confirmation_cancel))
                }
            },
        )
    }

    uiState.playbackError?.takeIf { pendingPlayback == null }?.let { error ->
        AlertDialog(
            onDismissRequest = stateHolder::dismissPlaybackError,
            title = { Text(stringResource(Res.string.memory_playback_error_title)) },
            text = {
                Text(
                    stringResource(
                        when (error) {
                            PlaybackError.MemoryNotFound ->
                                Res.string.memory_playback_memory_missing_message

                            PlaybackError.NoPendingConfirmation ->
                                Res.string.memory_playback_request_failed_message

                            PlaybackError.RecordingUnavailable ->
                                Res.string.memory_playback_recording_unavailable_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = stateHolder::dismissPlaybackError) {
                    Text(stringResource(Res.string.memory_dismiss))
                }
            },
        )
    }
}

@Composable
private fun MemoryListScreen(
    memories: List<Memory>,
    summaryModelMissing: Boolean,
    scrollState: ScrollState,
    playingTarget: PlaybackTarget?,
    onOpenMemory: (String) -> Unit,
    onPlaySummary: (String) -> Unit,
    onPlayRecording: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Text(
            stringResource(Res.string.memory_saved_memories_title),
            modifier = Modifier.padding(SavedMemoriesStateHolder.MEMORY_LIST_TITLE_PADDING),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState)
                .padding(SavedMemoriesStateHolder.MEMORY_LIST_CONTENT_PADDING),
            verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_LIST_ITEM_SPACING),
        ) {
            if (summaryModelMissing) {
                Text(
                    stringResource(Res.string.memory_summary_model_missing),
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            memories.forEach { memory ->
                MemoryCard(
                    memory = memory,
                    playingContent = playingTarget?.takeIf { it.memoryId == memory.id }?.content,
                    onOpen = { onOpenMemory(memory.id) },
                    onPlaySummary = { onPlaySummary(memory.id) },
                    onPlayRecording = { onPlayRecording(memory.id) },
                )
            }
        }

        Text(
            stringResource(Res.string.memory_return_to_recording),
            modifier = Modifier.fillMaxWidth()
                .padding(SavedMemoriesStateHolder.MEMORY_LIST_FOOTER_PADDING),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MemoryCard(
    memory: Memory,
    playingContent: PlaybackContent?,
    onOpen: () -> Unit,
    onPlaySummary: () -> Unit,
    onPlayRecording: () -> Unit,
) {
    val hasTranscript = memory.transcription.isNotBlank()
    val isFailed = !hasTranscript && memory.transcriptionStatus == TranscriptionStatus.FAILED
    val isPending = !hasTranscript && (
        memory.transcriptionStatus == TranscriptionStatus.PENDING ||
            memory.transcriptionStatus == TranscriptionStatus.IN_PROGRESS
        )
    Card(
        modifier = Modifier.fillMaxWidth()
            .then(
                if (isPending) {
                    Modifier.background(SavedMemoriesStateHolder.MEMORY_PENDING_CARD_BRUSH, SavedMemoriesStateHolder.MEMORY_CARD_SHAPE)
                } else {
                    Modifier
                },
            )
            .clickable(
                onClickLabel = stringResource(Res.string.memory_open_details),
                onClick = onOpen,
            )
            .animateContentSize(tween(SavedMemoriesStateHolder.MEMORY_CONTENT_SIZE_ANIMATION_MILLIS)),
        shape = SavedMemoriesStateHolder.MEMORY_CARD_SHAPE,
        colors = CardDefaults.cardColors(
            containerColor = if (isPending) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = SavedMemoriesStateHolder.MEMORY_CARD_SURFACE_ALPHA)
            },
        ),
        border = BorderStroke(
            SavedMemoriesStateHolder.MEMORY_PANEL_BORDER_WIDTH,
            if (isPending) {
                SavedMemoriesStateHolder.MEMORY_PENDING_CARD_BORDER_COLOR
            } else {
                Color.White.copy(alpha = SavedMemoriesStateHolder.MEMORY_PANEL_BORDER_ALPHA)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = SavedMemoriesStateHolder.MEMORY_PANEL_FLAT_ELEVATION),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SavedMemoriesStateHolder.MEMORY_CARD_CONTENT_PADDING),
            verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_CARD_CONTENT_SPACING),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AudioGlyph(playingContent = playingContent)
                Spacer(Modifier.width(SavedMemoriesStateHolder.MEMORY_CARD_GLYPH_SPACING))
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
                        formatMemoryTimestamp(memory.createdAtEpochMillis),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (isFailed) {
                Text(
                    stringResource(Res.string.memory_transcription_failed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
            }

            if (isPending) {
                Text(
                    stringResource(Res.string.memory_waiting_transcription),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = SavedMemoriesStateHolder.MEMORY_PENDING_TEXT_ALPHA,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
            }
            if (memory.summary.isNotBlank() && !isPending) {
                MemoryTextSection(
                    label = stringResource(Res.string.memory_summary),
                    text = memory.summary.preview(),
                    color = MaterialTheme.colorScheme.onSurface,
                    showIcon = true,
                )
            }
            if (memory.transcription.isNotBlank()) {
                MemoryTextSection(
                    label = stringResource(Res.string.memory_original_transcription),
                    text = memory.transcription.preview(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    showIcon = true,
                    isOriginal = true,
                )
            }
            AudioPlaybackButtons(
                playingContent = playingContent,
                hasSummary = !isPending && memory.summary.isNotBlank(),
                onPlayRecording = onPlayRecording,
                onPlaySummary = onPlaySummary,
            )
        }
    }
}

@Composable
private fun MemoryDetailScreen(
    memory: Memory,
    scrollState: ScrollState,
    playingContent: PlaybackContent?,
    onBack: () -> Unit,
    onPlaySummary: () -> Unit,
    onPlayRecording: () -> Unit,
    onReturnToCapture: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(SavedMemoriesStateHolder.MEMORY_DETAIL_NAVIGATION_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(Res.string.memory_back_to_memories))
            }
            TextButton(onClick = onReturnToCapture) {
                Text(stringResource(Res.string.memory_capture))
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState)
                .padding(SavedMemoriesStateHolder.MEMORY_DETAIL_CONTENT_PADDING),
            verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_DETAIL_CONTENT_SPACING),
        ) {
            AuroraPanel(
                shape = SavedMemoriesStateHolder.MEMORY_DETAIL_HEADER_SHAPE,
                containerColor = Color.White.copy(
                    alpha = SavedMemoriesStateHolder.MEMORY_DETAIL_HEADER_SURFACE_ALPHA,
                ),
                elevation = SavedMemoriesStateHolder.MEMORY_PANEL_FLAT_ELEVATION,
            ) {
                Text(
                    formatMemoryTimestamp(memory.createdAtEpochMillis),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    memory.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            AuroraPanel(
                shape = SavedMemoriesStateHolder.MEMORY_DETAIL_PLAYBACK_SHAPE,
                containerColor = Color.White.copy(
                    alpha = SavedMemoriesStateHolder.MEMORY_DETAIL_HEADER_SURFACE_ALPHA,
                ),
                contentPadding = SavedMemoriesStateHolder.MEMORY_DETAIL_PLAYBACK_PADDING,
                elevation = SavedMemoriesStateHolder.MEMORY_PANEL_FLAT_ELEVATION,
            ) {
                AudioPlaybackButtons(
                    playingContent = playingContent,
                    hasSummary = memory.summary.isNotBlank(),
                    onPlayRecording = onPlayRecording,
                    onPlaySummary = onPlaySummary,
                )
            }
            if (memory.summary.isNotBlank()) {
                AuroraPanel(
                    shape = SavedMemoriesStateHolder.MEMORY_DETAIL_SUMMARY_SHAPE,
                    containerColor = Color.White.copy(
                        alpha = SavedMemoriesStateHolder.MEMORY_DETAIL_SUMMARY_SURFACE_ALPHA,
                    ),
                ) {
                    MemoryTextSection(
                        label = stringResource(Res.string.memory_summary),
                        text = memory.summary,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (memory.transcription.isNotBlank()) {
                AuroraPanel(
                    shape = SavedMemoriesStateHolder.MEMORY_DETAIL_TRANSCRIPTION_SHAPE,
                ) {
                    MemoryTextSection(
                        label = stringResource(Res.string.memory_original_transcription),
                        text = memory.transcription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        isOriginal = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryTextSection(
    label: String,
    text: String,
    color: Color,
    showIcon: Boolean = false,
    isOriginal: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_TEXT_SECTION_SPACING)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_TEXT_LABEL_SPACING),
        ) {
            if (showIcon) MemorySectionIcon(original = isOriginal)
            Text(
                label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = SavedMemoriesStateHolder.MEMORY_TEXT_LABEL_LETTER_SPACING,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun MemorySectionIcon(original: Boolean) {
    Box(
        modifier = Modifier.size(SavedMemoriesStateHolder.MEMORY_SECTION_ICON_SIZE)
            .clip(SavedMemoriesStateHolder.MEMORY_SECTION_ICON_SHAPE)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (original) "≋" else "▤",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AuroraPanel(
    shape: Shape = SavedMemoriesStateHolder.MEMORY_PANEL_DEFAULT_SHAPE,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = SavedMemoriesStateHolder.MEMORY_PANEL_DEFAULT_CONTENT_PADDING,
    elevation: Dp = SavedMemoriesStateHolder.MEMORY_PANEL_DEFAULT_ELEVATION,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
        border = BorderStroke(
            SavedMemoriesStateHolder.MEMORY_PANEL_BORDER_WIDTH,
            Color.White.copy(alpha = SavedMemoriesStateHolder.MEMORY_PANEL_BORDER_ALPHA),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_CARD_CONTENT_SPACING),
            content = content,
        )
    }
}

@Composable
private fun AudioPlaybackButtons(
    playingContent: PlaybackContent?,
    hasSummary: Boolean,
    onPlayRecording: () -> Unit,
    onPlaySummary: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PlaybackButton(
            playing = playingContent == PlaybackContent.RECORDING,
            playLabel = Res.string.memory_play_recording,
            stopLabel = Res.string.memory_stop_recording,
            onClick = onPlayRecording,
        )
        if (hasSummary) {
            PlaybackButton(
                playing = playingContent == PlaybackContent.SUMMARY,
                playLabel = Res.string.memory_play_summary,
                stopLabel = Res.string.memory_stop_summary,
                onClick = onPlaySummary,
            )
        }
    }
}

@Composable
private fun PlaybackButton(
    playing: Boolean,
    playLabel: StringResource,
    stopLabel: StringResource,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(
            SavedMemoriesStateHolder.MEMORY_PLAYBACK_BUTTON_BORDER_WIDTH,
            MaterialTheme.colorScheme.primary.copy(
                alpha = SavedMemoriesStateHolder.MEMORY_PLAYBACK_BUTTON_BORDER_ALPHA,
            ),
        ),
        contentPadding = SavedMemoriesStateHolder.MEMORY_PLAYBACK_BUTTON_CONTENT_PADDING,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (playing) "■" else "▶",
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.width(SavedMemoriesStateHolder.MEMORY_PLAYBACK_BUTTON_ICON_SPACING))
            Text(
                stringResource(if (playing) stopLabel else playLabel),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun AudioGlyph(playingContent: PlaybackContent?) {
    val description = stringResource(
        when (playingContent) {
            PlaybackContent.TRANSCRIPTION -> Res.string.memory_audio_playing
            PlaybackContent.SUMMARY -> Res.string.memory_summary_audio_playing
            PlaybackContent.RECORDING -> Res.string.memory_recording_audio_playing
            null -> Res.string.memory_audio_not_playing
        },
    )
    Box(
        modifier = Modifier.size(SavedMemoriesStateHolder.MEMORY_AUDIO_GLYPH_SIZE).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics {
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (playingContent != null) "Ⅱ" else "▶",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun String.preview(): String =
    if (length > SavedMemoriesStateHolder.MEMORY_PREVIEW_CHAR_LIMIT) {
        take(SavedMemoriesStateHolder.MEMORY_PREVIEW_CHAR_LIMIT).trimEnd() + "..."
    } else {
        this
    }
