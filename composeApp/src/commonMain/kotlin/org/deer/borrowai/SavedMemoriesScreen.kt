package org.deer.borrowai

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.CategoryFilterChip
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackTarget
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_dismiss
import borrowai.composeapp.generated.resources.memory_open_details
import borrowai.composeapp.generated.resources.memory_original_transcription
import borrowai.composeapp.generated.resources.memory_playback_confirmation_cancel
import borrowai.composeapp.generated.resources.memory_playback_confirmation_confirm
import borrowai.composeapp.generated.resources.memory_playback_confirmation_message
import borrowai.composeapp.generated.resources.memory_playback_confirmation_title
import borrowai.composeapp.generated.resources.memory_playback_error_title
import borrowai.composeapp.generated.resources.memory_playback_memory_missing_message
import borrowai.composeapp.generated.resources.memory_playback_recording_unavailable_message
import borrowai.composeapp.generated.resources.memory_playback_request_failed_message
import borrowai.composeapp.generated.resources.memory_playback_resume_failed_message
import borrowai.composeapp.generated.resources.memory_pause_recording
import borrowai.composeapp.generated.resources.memory_play_recording
import borrowai.composeapp.generated.resources.memory_saved_memories_title
import borrowai.composeapp.generated.resources.memory_stop_recording
import borrowai.composeapp.generated.resources.memory_transcription_failed
import borrowai.composeapp.generated.resources.memory_waiting_transcription
import borrowai.composeapp.generated.resources.memory_summary
import borrowai.composeapp.generated.resources.memory_summary_model_missing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SavedMemoriesScreen(
    services: AppServices,
    onDetailBackRegistered: (() -> Unit) -> Unit,
    onDetailVisibilityChanged: (Boolean) -> Unit,
    onGoToRecord: () -> Unit,
) {
    val memoryPagerState = rememberPagerState(
        pageCount = { SavedMemoriesStateHolder.MEMORY_PAGE_COUNT },
    )
    val memoriesListState = rememberLazyListState()
    val detailScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stateHolder = remember(
        services,
        memoryPagerState,
        memoriesListState,
        detailScrollState,
        scope,
    ) {
        SavedMemoriesStateHolder(
            memoryService = services.memories,
            mediaSessionService = services.mediaSession,
            speakers = services.speakers,
            speakerAnalysisActive = services::speakerAnalysisActive,
            pagerState = memoryPagerState,
            memoriesListState = memoriesListState,
            detailScrollState = detailScrollState,
            scope = scope,
            launchWrite = services::launchWrite,
            summaryModelMissing = services.summaryModelMissing,
        )
    }
    val uiState by stateHolder.uiState.collectAsState()
    val playbackSpeed by stateHolder.playbackSpeed.collectAsState()
    val currentOnDetailVisibilityChanged by rememberUpdatedState(onDetailVisibilityChanged)
    val selectedMemory = uiState.memories.firstOrNull { it.id == uiState.selectedMemoryId }

    // A pending delete is written when this page leaves composition (the Memories tab was left) and when the app stops.
    DisposableEffect(stateHolder) {
        onDispose { stateHolder.commitPendingDelete() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stateHolder.commitPendingDelete() }

    val snackbarHostState = remember { SnackbarHostState() }
    val headingFocus = remember { FocusRequester() }
    // After a delete, focus moves to the list heading once the list is back on screen.
    LaunchedEffect(uiState.pendingDelete.undoWindow?.requestId) {
        if (uiState.pendingDelete.undoWindow == null) return@LaunchedEffect
        snapshotFlow { stateHolder.pagerState.settledPage }.first { it == SavedMemoriesStateHolder.MEMORY_LIST_PAGE }
        // requestFocus throws if the heading is not attached yet; focus then stays where it is.
        runCatching { headingFocus.requestFocus() }
    }

    androidx.compose.runtime.LaunchedEffect(stateHolder) {
        onDetailBackRegistered(stateHolder::backToList)
    }
    androidx.compose.runtime.LaunchedEffect(stateHolder.pagerState) {
        snapshotFlow { stateHolder.pagerState.targetPage }.collect(stateHolder::onPagerTargetPageChanged)
    }
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

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = stateHolder.pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = uiState.detailSwipeEnabled,
        ) { page ->
            when (page) {
                SavedMemoriesStateHolder.MEMORY_LIST_PAGE -> MemoryListScreen(
                    memories = uiState.listMemories,
                    libraryCount = uiState.visibleMemories.size,
                    noResults = memoryNoResults(uiState.searchResults),
                    isFiltered = uiState.searchResults.isFiltered,
                    searchText = uiState.searchText,
                    searchTextCut = uiState.searchTextCut,
                    summaryModelMissing = uiState.summaryModelMissing,
                    categoryNames = uiState.categoryNames,
                    listState = stateHolder.memoriesListState,
                    speakerChips = uiState.speakerChips,
                    playingTarget = (uiState.mediaSessionState as? MediaSessionState.Playing)
                        ?.takeUnless { it.paused }
                        ?.target,
                    playbackControlsAvailable = stateHolder.playbackControlsAvailable,
                    onOpenMemory = stateHolder::openMemory,
                    onOpenMemoryAtSpeaker = stateHolder::openMemoryAtSpeaker,
                    onPlayRecording = { stateHolder.togglePlayback(it, PlaybackContent.RECORDING) },
                    onSearchTextChanged = stateHolder::onSearchTextChanged,
                    onSearchSubmit = stateHolder::submitSearch,
                    onClearSearch = stateHolder::clearSearch,
                    categoryChips = uiState.categoryChips,
                    selectedCategoryIds = uiState.activeCategoryIds,
                    onToggleCategory = stateHolder::toggleCategory,
                    onClearCategoryFilters = stateHolder::clearCategoryFilters,
                    showEmptyState = uiState.showsEmptyMemories,
                    headingFocus = headingFocus,
                    onGoToRecord = onGoToRecord,
                )

                SavedMemoriesStateHolder.MEMORY_DETAIL_PAGE -> selectedMemory?.let { memory ->
                    MemoryDetailScreen(
                        memory = memory,
                        scrollState = stateHolder.detailScrollState,
                        playback = (uiState.mediaSessionState as? MediaSessionState.Playing)
                            ?.takeIf { it.target.memoryId == memory.id && it.target.content == PlaybackContent.RECORDING },
                        playbackControlsAvailable = stateHolder.playbackControlsAvailable,
                        onPlayRecording = {
                            stateHolder.togglePlayback(memory.id, PlaybackContent.RECORDING)
                        },
                        onSeekPlayback = stateHolder::seekPlayback,
                        onSkipPlayback = stateHolder::skipPlayback,
                        playbackSpeed = playbackSpeed,
                        onSetPlaybackSpeed = stateHolder::setPlaybackSpeed,
                        services = services,
                        playingSample = (uiState.mediaSessionState as? MediaSessionState.Playing)
                            ?.takeIf { it.target.memoryId == memory.id && it.target.sample != null }
                            ?.target,
                        onPlaySample = { stateHolder.toggleSamplePlayback(it) },
                        focusedSpeakerOrdinal = uiState.focusedSpeakerOrdinal,
                        onSpeakerFocusHandled = stateHolder::clearFocusedSpeaker,
                        onDelete = { stateHolder.deleteFromDetail(memory.id) },
                    )
                }

                else -> Unit
            }
        }
        // Sits above the dock, because this page is already padded for the dock (App.kt).
        MemoryDeleteSnackbarHost(
            hostState = snackbarHostState,
            undoWindow = uiState.pendingDelete.undoWindow,
            deleteFailures = uiState.deleteFailures,
            onUndo = stateHolder::undoDelete,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
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

                            PlaybackError.ResumeFailed, PlaybackError.AudioFocusHeld ->
                                Res.string.memory_playback_resume_failed_message
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
    libraryCount: Int,
    isFiltered: Boolean,
    searchText: String,
    searchTextCut: Boolean,
    summaryModelMissing: Boolean,
    categoryNames: Map<Int, String>,
    listState: LazyListState,
    speakerChips: Map<String, SpeakerChipsState>,
    playingTarget: PlaybackTarget?,
    playbackControlsAvailable: Boolean,
    onOpenMemory: (String) -> Unit,
    onOpenMemoryAtSpeaker: (String, Int?) -> Unit,
    onPlayRecording: (String) -> Unit,
    onSearchTextChanged: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onClearSearch: () -> Unit,
    categoryChips: List<CategoryFilterChip>,
    selectedCategoryIds: Set<Int>,
    onToggleCategory: (Int) -> Unit,
    onClearCategoryFilters: () -> Unit,
    showEmptyState: Boolean,
    headingFocus: FocusRequester,
    onGoToRecord: () -> Unit,
    noResults: MemoryNoResults?,
) {
    MemoryListSearchKeys(listState = listState, searchText = searchText, onClearSearch = onClearSearch)
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Text(
            stringResource(Res.string.memory_saved_memories_title),
            modifier = Modifier
                .padding(SavedMemoriesStateHolder.MEMORY_LIST_TITLE_PADDING)
                .focusRequester(headingFocus)
                .focusable(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        if (libraryCount > 0) {
            MemorySearchField(
                text = searchText,
                inputCut = searchTextCut,
                onTextChange = onSearchTextChanged,
                onSearch = onSearchSubmit,
                onClear = onClearSearch,
                modifier = Modifier.padding(MemorySearchControlsPadding),
            )
            if (categoryChips.isNotEmpty()) {
                CategoryFilterRow(
                    chips = categoryChips,
                    selectedIds = selectedCategoryIds,
                    onToggle = onToggleCategory,
                    onClear = onClearCategoryFilters,
                    modifier = Modifier.padding(MemorySearchControlsPadding),
                )
            }
            MemoryCountLine(
                shown = memories.size,
                total = libraryCount,
                isFiltered = isFiltered,
                modifier = Modifier.padding(MemorySearchControlsPadding),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = SavedMemoriesStateHolder.MEMORY_LIST_CONTENT_PADDING,
            verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_LIST_ITEM_SPACING),
        ) {
            if (summaryModelMissing) {
                item {
                    Text(
                        stringResource(Res.string.memory_summary_model_missing),
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            noResults?.let { result ->
                item {
                    MemoryNoResultsContent(
                        noResults = result,
                        hasCategoryFilter = selectedCategoryIds.isNotEmpty(),
                        onClearSearch = onClearSearch,
                        onClearFilters = onClearCategoryFilters,
                    )
                }
            }
            items(memories, key = Memory::id) { memory ->
                MemoryCard(
                    memory = memory,
                    categoryNames = memory.categories.mapNotNull { categoryNames[it] },
                    playingContent = playingTarget?.takeIf { it.memoryId == memory.id }?.content,
                    playbackControlsAvailable = playbackControlsAvailable,
                    speakerChips = speakerChips[memory.id],
                    onOpen = { onOpenMemory(memory.id) },
                    onOpenSpeaker = { ordinal -> onOpenMemoryAtSpeaker(memory.id, ordinal) },
                    onPlayRecording = { onPlayRecording(memory.id) },
                )
            }
            if (showEmptyState) {
                item { MemoriesEmptyState(onGoToRecord) }
            }
        }
    }
}

@Composable
private fun MemoryCard(
    memory: Memory,
    categoryNames: List<String>,
    playingContent: PlaybackContent?,
    playbackControlsAvailable: Boolean,
    speakerChips: SpeakerChipsState?,
    onOpen: () -> Unit,
    onOpenSpeaker: (Int?) -> Unit,
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

            if (categoryNames.isNotEmpty()) {
                CategoryChips(names = categoryNames)
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
            if (speakerRecognitionSupported) {
                speakerChips?.let { SpeakerChipsRow(state = it, onOpenSpeaker = onOpenSpeaker) }
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
                playbackControlsAvailable = playbackControlsAvailable,
                onPlayRecording = onPlayRecording,
            )
        }
    }
}

@Composable
private fun MemoryDetailScreen(
    memory: Memory,
    services: AppServices,
    scrollState: ScrollState,
    playback: MediaSessionState.Playing?,
    playbackControlsAvailable: Boolean,
    onPlayRecording: () -> Unit,
    onSeekPlayback: (Long) -> Unit,
    onSkipPlayback: (Long) -> Unit,
    playbackSpeed: Float,
    onSetPlaybackSpeed: (Float) -> Unit,
    playingSample: PlaybackTarget?,
    onPlaySample: (PlaybackTarget) -> Unit,
    focusedSpeakerOrdinal: Int?,
    onSpeakerFocusHandled: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
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
                Row(verticalAlignment = Alignment.Top) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_CARD_CONTENT_SPACING),
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
                    MemoryMoreActions(onDelete = onDelete)
                }
            }
            AuroraPanel(
                shape = SavedMemoriesStateHolder.MEMORY_DETAIL_PLAYBACK_SHAPE,
                containerColor = Color.White.copy(
                    alpha = SavedMemoriesStateHolder.MEMORY_DETAIL_HEADER_SURFACE_ALPHA,
                ),
                contentPadding = SavedMemoriesStateHolder.MEMORY_DETAIL_PLAYBACK_PADDING,
                elevation = SavedMemoriesStateHolder.MEMORY_PANEL_FLAT_ELEVATION,
            ) {
                if (playbackControlsAvailable) {
                    PlaybackPanel(
                        playback = playback,
                        durationMillis = playback?.durationMillis,
                        onTogglePlayback = onPlayRecording,
                        onSeek = onSeekPlayback,
                        onSkip = onSkipPlayback,
                        speed = playbackSpeed,
                        onSpeedSelected = onSetPlaybackSpeed,
                    )
                } else {
                    AudioPlaybackButtons(
                        playingContent = playback?.target?.content,
                        playbackControlsAvailable = false,
                        onPlayRecording = onPlayRecording,
                    )
                }
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
            if (speakerRecognitionSupported) {
                SpeakersSection(
                    memory = memory,
                    speakers = services.speakers,
                    hasRecordingFile = services.mediaSession::hasRecordingFile,
                    playingTarget = playingSample,
                    onPlaySample = onPlaySample,
                    focusedSpeakerOrdinal = focusedSpeakerOrdinal,
                    onSpeakerFocusHandled = onSpeakerFocusHandled,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(names: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_SPACING),
        verticalArrangement = Arrangement.spacedBy(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_SPACING),
    ) {
        names.forEach { name ->
            Text(
                name,
                modifier = Modifier
                    .clip(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_SHAPE)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_ALPHA))
                    .padding(SavedMemoriesStateHolder.MEMORY_CATEGORY_CHIP_PADDING),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
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
internal fun AuroraPanel(
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
    playbackControlsAvailable: Boolean,
    onPlayRecording: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PlaybackButton(
            playing = playingContent == PlaybackContent.RECORDING,
            playLabel = Res.string.memory_play_recording,
            playingLabel = if (playbackControlsAvailable) {
                Res.string.memory_pause_recording
            } else {
                Res.string.memory_stop_recording
            },
            onClick = onPlayRecording,
        )
    }
}

@Composable
internal fun PlaybackButton(
    playing: Boolean,
    playLabel: StringResource,
    playingLabel: StringResource,
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
                stringResource(if (playing) playingLabel else playLabel),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun String.preview(): String =
    if (length > SavedMemoriesStateHolder.MEMORY_PREVIEW_CHAR_LIMIT) {
        take(SavedMemoriesStateHolder.MEMORY_PREVIEW_CHAR_LIMIT).trimEnd() + "..."
    } else {
        this
    }
