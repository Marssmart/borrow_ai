package org.deer.borrowai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.speaker_management_action_failed
import borrowai.composeapp.generated.resources.speaker_management_add
import borrowai.composeapp.generated.resources.speaker_management_assign
import borrowai.composeapp.generated.resources.speaker_management_assign_title
import borrowai.composeapp.generated.resources.speaker_management_assigned
import borrowai.composeapp.generated.resources.speaker_management_audio_unavailable
import borrowai.composeapp.generated.resources.speaker_management_back
import borrowai.composeapp.generated.resources.speaker_management_cancel
import borrowai.composeapp.generated.resources.speaker_management_change
import borrowai.composeapp.generated.resources.speaker_management_choose_another
import borrowai.composeapp.generated.resources.speaker_management_choose_speaker
import borrowai.composeapp.generated.resources.speaker_management_clear_search
import borrowai.composeapp.generated.resources.speaker_management_close
import borrowai.composeapp.generated.resources.speaker_management_create_assign
import borrowai.composeapp.generated.resources.speaker_management_create_assign_action
import borrowai.composeapp.generated.resources.speaker_management_delete
import borrowai.composeapp.generated.resources.speaker_management_delete_confirm
import borrowai.composeapp.generated.resources.speaker_management_delete_impact
import borrowai.composeapp.generated.resources.speaker_management_delete_title
import borrowai.composeapp.generated.resources.speaker_management_duplicate
import borrowai.composeapp.generated.resources.speaker_management_empty_body
import borrowai.composeapp.generated.resources.speaker_management_empty_title
import borrowai.composeapp.generated.resources.speaker_management_find_memory
import borrowai.composeapp.generated.resources.speaker_management_last_heard
import borrowai.composeapp.generated.resources.speaker_management_leave_unknown
import borrowai.composeapp.generated.resources.speaker_management_linked_count
import borrowai.composeapp.generated.resources.speaker_management_load_failed
import borrowai.composeapp.generated.resources.speaker_management_matched
import borrowai.composeapp.generated.resources.speaker_management_merge
import borrowai.composeapp.generated.resources.speaker_management_merge_confirm
import borrowai.composeapp.generated.resources.speaker_management_merge_impact
import borrowai.composeapp.generated.resources.speaker_management_merge_title
import borrowai.composeapp.generated.resources.speaker_management_missing_occurrence
import borrowai.composeapp.generated.resources.speaker_management_name
import borrowai.composeapp.generated.resources.speaker_management_name_count
import borrowai.composeapp.generated.resources.speaker_management_name_helper
import borrowai.composeapp.generated.resources.speaker_management_no_assignable
import borrowai.composeapp.generated.resources.speaker_management_no_memories
import borrowai.composeapp.generated.resources.speaker_management_no_recent
import borrowai.composeapp.generated.resources.speaker_management_no_results
import borrowai.composeapp.generated.resources.speaker_management_not_this_person
import borrowai.composeapp.generated.resources.speaker_management_open_memory
import borrowai.composeapp.generated.resources.speaker_management_play_sample
import borrowai.composeapp.generated.resources.speaker_management_recent
import borrowai.composeapp.generated.resources.speaker_management_rename
import borrowai.composeapp.generated.resources.speaker_management_retry
import borrowai.composeapp.generated.resources.speaker_management_save
import borrowai.composeapp.generated.resources.speaker_management_search
import borrowai.composeapp.generated.resources.speaker_management_select_target
import borrowai.composeapp.generated.resources.speaker_management_settings
import borrowai.composeapp.generated.resources.speaker_management_stop_sample
import borrowai.composeapp.generated.resources.speaker_management_title
import borrowai.composeapp.generated.resources.speaker_management_untitled
import org.deer.borrowai.data.domain.MAX_SPEAKER_NAME_LENGTH
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerOccurrenceLink
import org.deer.borrowai.data.domain.SpeakerSummary
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackSample
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.SpeakerError
import org.deer.borrowai.data.service.SpeakerService
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class SpeakerManagementRequest(
    val occurrence: SpeakerOccurrence? = null,
    val showRejectChoice: Boolean = false,
)

internal data class SpeakerMemoryNavigation(val memoryId: String, val ordinal: Int)

@Composable
internal fun SpeakerManagementScreen(
    speakers: SpeakerService,
    memories: MemoryService,
    request: SpeakerManagementRequest,
    onClose: () -> Unit,
    onOccurrenceChanged: (SpeakerOccurrenceLink, SpeakerOccurrenceLink) -> Unit,
    onOccurrenceUnavailable: () -> Unit,
    onOpenMemoryAtSpeaker: (SpeakerMemoryNavigation) -> Unit,
    onOpenSettings: () -> Unit,
    onPlaySample: (PlaybackTarget) -> Unit,
    playingTarget: PlaybackTarget?,
    hasRecordingFile: (Memory) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf<List<Speaker>>(emptyList()) }
    var summaries by remember { mutableStateOf<Map<String, SpeakerSummary>>(emptyMap()) }
    var memoryList by remember { mutableStateOf<List<Memory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedOccurrences by remember { mutableStateOf<List<SpeakerOccurrence>>(emptyList()) }
    var detailFailed by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var assignmentSearch by remember { mutableStateOf("") }
    var operationFailed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var createVisible by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Speaker?>(null) }
    var name by remember { mutableStateOf("") }
    var mergeMode by remember { mutableStateOf(false) }
    var mergeSearch by remember { mutableStateOf("") }
    var mergeTarget by remember { mutableStateOf<Speaker?>(null) }
    var deleteTarget by remember { mutableStateOf<Speaker?>(null) }
    var retryAssignSpeakerId by remember { mutableStateOf<String?>(null) }
    var rejectChoiceVisible by remember(request) { mutableStateOf(request.showRejectChoice) }

    suspend fun reload() {
        loading = true
        loadFailed = false
        memoryList = memories.getState().first()
        val speakerResult = speakers.findSpeakers()
        val summaryResult = speakers.findSpeakerSummaries(
            memoryList.associate { it.id to it.createdAtEpochMillis },
        )
        if (speakerResult.isLeft() || summaryResult.isLeft()) {
            loading = false
            loadFailed = true
            return
        }
        library = speakerResult.getOrNull().orEmpty()
        summaries = summaryResult.getOrNull().orEmpty()
        loading = false
        selectedId?.let { id ->
            val occurrenceResult = speakers.findOccurrencesForSpeaker(id)
            if (occurrenceResult.isLeft()) {
                detailFailed = true
            } else {
                selectedOccurrences = occurrenceResult.getOrNull().orEmpty()
                detailFailed = false
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SpeakerAssignmentSheet(
        speakers: SpeakerService,
        memories: MemoryService,
        occurrence: SpeakerOccurrence,
        rejectChoice: Boolean,
        onClose: () -> Unit,
        onOccurrenceChanged: (SpeakerOccurrenceLink, SpeakerOccurrenceLink) -> Unit,
        onOccurrenceUnavailable: () -> Unit,
        onOpenSettings: () -> Unit,
        onPlaySample: (PlaybackTarget) -> Unit,
        playingTarget: PlaybackTarget?,
        hasRecordingFile: (Memory) -> Boolean,
    ) {
        val scope = rememberCoroutineScope()
        var library by remember { mutableStateOf<List<Speaker>>(emptyList()) }
        var summaries by remember { mutableStateOf<Map<String, SpeakerSummary>>(emptyMap()) }
        var sourceMemory by remember { mutableStateOf<Memory?>(null) }
        var loading by remember { mutableStateOf(true) }
        var loadFailed by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var createVisible by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var operationError by remember { mutableStateOf<String?>(null) }
        var retrySpeakerId by remember { mutableStateOf<String?>(null) }
        var rejectChoiceVisible by remember(rejectChoice) { mutableStateOf(rejectChoice) }

        suspend fun reload() {
            loading = true
            loadFailed = false
            val currentMemories = memories.getState().first()
            val speakerResult = speakers.findSpeakers()
            val summaryResult = speakers.findSpeakerSummaries(
                currentMemories.associate { it.id to it.createdAtEpochMillis },
            )
            if (speakerResult.isLeft() || summaryResult.isLeft()) {
                loading = false
                loadFailed = true
                return
            }
            library = speakerResult.getOrNull().orEmpty()
            summaries = summaryResult.getOrNull().orEmpty()
            sourceMemory = currentMemories.firstOrNull { it.id == occurrence.memoryId }
            loading = false
            if (sourceMemory == null) {
                onOccurrenceUnavailable()
                onClose()
            }
        }

        LaunchedEffect(speakers) {
            speakers.changes.onStart { emit(Unit) }.collect { reload() }
        }
        LaunchedEffect(memories, occurrence.id) {
            memories.getState().collect { current ->
                if (current.none { it.id == occurrence.memoryId }) {
                    onOccurrenceUnavailable()
                    onClose()
                }
            }
        }

        fun assignmentSnapshot(speakerId: String): SpeakerOccurrenceLink =
            SpeakerOccurrenceLink(
                occurrenceId = occurrence.id,
                speakerId = speakerId,
                link = SpeakerLink.MANUAL,
                rejectedSpeakerIds = occurrence.rejectedSpeakerIds.filterNot { it == speakerId },
            )

        fun completeAssignment(speakerId: String) {
            scope.launch {
                speakers.assignOccurrence(occurrence.id, speakerId).fold(
                    ifLeft = {
                        if (it is SpeakerError.NotFound) {
                            operationError = "This speaker occurrence is no longer available."
                        } else {
                            operationError = it.toUiMessage()
                        }
                        retrySpeakerId = speakerId
                    },
                    ifRight = {
                        onOccurrenceChanged(assignmentSnapshot(speakerId), occurrence.toLinkSnapshot())
                        onClose()
                    },
                )
            }
        }

        ModalBottomSheet(onDismissRequest = onClose) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(Res.string.speaker_management_assign_title, occurrence.ordinal),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    sourceMemory?.title?.ifBlank { stringResource(Res.string.speaker_management_untitled) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                sourceMemory?.let { memory ->
                    if (hasRecordingFile(memory)) {
                        val target = sampleTarget(occurrence)
                        TextButton(onClick = { onPlaySample(target) }) {
                            Text(
                                stringResource(
                                    if (playingTarget == target) Res.string.speaker_management_stop_sample
                                    else Res.string.speaker_management_play_sample,
                                ),
                                modifier = Modifier.semantics {
                                    contentDescription = "${if (playingTarget == target) "Stop" else "Play"} sample for Speaker ${occurrence.ordinal} in ${memory.title}"
                                },
                            )
                        }
                    } else {
                        Text(stringResource(Res.string.speaker_management_audio_unavailable))
                    }
                }
                operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (loading) {
                    LoadingSpeakerRows(Modifier.weight(1f))
                } else if (loadFailed) {
                    ErrorPanel(onRetry = { scope.launch { reload() } })
                } else if (rejectChoiceVisible) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    speakers.rejectAutomaticMatch(occurrence.id).fold(
                                        ifLeft = { operationError = it.toUiMessage() },
                                        ifRight = {
                                            val after = SpeakerOccurrenceLink(
                                                occurrenceId = occurrence.id,
                                                speakerId = null,
                                                link = SpeakerLink.NONE,
                                                rejectedSpeakerIds = (occurrence.rejectedSpeakerIds +
                                                    requireNotNull(occurrence.speakerId)).distinct(),
                                            )
                                            onOccurrenceChanged(after, occurrence.toLinkSnapshot())
                                            onClose()
                                        },
                                    )
                                }
                            },
                        ) { Text(stringResource(Res.string.speaker_management_leave_unknown)) }
                        TextButton(onClick = { rejectChoiceVisible = false }) {
                            Text(stringResource(Res.string.speaker_management_choose_another))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(Res.string.speaker_management_search)) },
                        singleLine = true,
                    )
                    val candidates = library.filter { it.name.contains(search, ignoreCase = true) }
                    if (library.isEmpty()) {
                        Text(stringResource(Res.string.speaker_management_no_assignable))
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(Res.string.speaker_management_settings))
                        }
                    } else if (candidates.isEmpty()) {
                        Text(stringResource(Res.string.speaker_management_no_results, search))
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(candidates, key = Speaker::id) { speaker ->
                            SpeakerRow(speaker, summaries[speaker.id]) {
                                completeAssignment(speaker.id)
                            }
                        }
                    }
                    Button(
                        onClick = { name = ""; createVisible = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(Res.string.speaker_management_create_assign)) }
                    retrySpeakerId?.let { id ->
                        TextButton(onClick = { completeAssignment(id) }) {
                            Text(stringResource(Res.string.speaker_management_retry))
                        }
                    }
                }
            }
        }

        if (createVisible) {
            AlertDialog(
                onDismissRequest = { createVisible = false },
                title = { Text(stringResource(Res.string.speaker_management_add)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= MAX_SPEAKER_NAME_LENGTH) name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.speaker_management_name)) },
                            supportingText = {
                                Column {
                                    Text(stringResource(Res.string.speaker_management_name_helper))
                                    Text(
                                        stringResource(
                                            Res.string.speaker_management_name_count,
                                            name.length,
                                            MAX_SPEAKER_NAME_LENGTH,
                                        ),
                                    )
                                }
                            },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = name.trim().isNotEmpty(),
                        onClick = {
                            scope.launch {
                                speakers.createSpeaker(name).fold(
                                    ifLeft = { operationError = it.toUiMessage() },
                                    ifRight = { created ->
                                        createVisible = false
                                        reload()
                                        completeAssignment(created.id)
                                    },
                                )
                            }
                        },
                    ) { Text(stringResource(Res.string.speaker_management_create_assign_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { createVisible = false }) {
                        Text(stringResource(Res.string.speaker_management_cancel))
                    }
                },
            )
        }
    }

    request.occurrence?.let { occurrence ->
        SpeakerAssignmentSheet(
            speakers = speakers,
            memories = memories,
            occurrence = occurrence,
            rejectChoice = request.showRejectChoice,
            onClose = onClose,
            onOccurrenceChanged = onOccurrenceChanged,
            onOccurrenceUnavailable = onOccurrenceUnavailable,
            onOpenSettings = onOpenSettings,
            onPlaySample = onPlaySample,
            playingTarget = playingTarget,
            hasRecordingFile = hasRecordingFile,
        )
        return
    }

    LaunchedEffect(speakers, memories) {
        speakers.changes.onStart { emit(Unit) }.collect { reload() }
    }
    LaunchedEffect(request) {
        request.occurrence?.let { occurrence ->
            memories.getState().collect { currentMemories ->
                if (currentMemories.none { it.id == occurrence.memoryId }) {
                    onOccurrenceUnavailable()
                    onClose()
                    return@collect
                }
            }
        }
    }

    val selectedSpeaker = library.firstOrNull { it.id == selectedId }
    val sourceOccurrence = request.occurrence
    val sourceMemory = sourceOccurrence?.let { occurrence ->
        memoryList.firstOrNull { it.id == occurrence.memoryId }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (sourceOccurrence != null) {
                        stringResource(Res.string.speaker_management_assign_title, sourceOccurrence.ordinal)
                    } else {
                        stringResource(Res.string.speaker_management_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onClose) {
                    Text(stringResource(Res.string.speaker_management_close))
                }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            when {
                sourceOccurrence != null -> {
                    Text(
                        sourceMemory?.title?.ifBlank { "Untitled memory" } ?: "Memory",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    sourceMemory?.let { memory ->
                        if (hasRecordingFile(memory)) {
                            val target = sampleTarget(sourceOccurrence)
                            TextButton(onClick = { onPlaySample(target) }) {
                                Text(
                                    stringResource(
                                        if (playingTarget == target) Res.string.speaker_management_stop_sample
                                        else Res.string.speaker_management_play_sample,
                                    ),
                                    modifier = Modifier.semantics {
                                        contentDescription = "${if (playingTarget == target) "Stop" else "Play"} sample for Speaker ${sourceOccurrence.ordinal}, ${memory.title}"
                                    },
                                )
                            }
                        } else {
                            Text(stringResource(Res.string.speaker_management_audio_unavailable))
                        }
                    }
                    if (loading) {
                        LoadingSpeakerRows(Modifier.weight(1f))
                    } else if (loadFailed) {
                        ErrorPanel(onRetry = { scope.launch { reload() } })
                    } else {
                        OutlinedTextField(
                            value = assignmentSearch,
                            onValueChange = { assignmentSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.speaker_management_search)) },
                            singleLine = true,
                        )
                        val assignable = library.filter { it.name.contains(assignmentSearch, ignoreCase = true) }
                        if (library.isEmpty()) {
                            Column {
                                Text(stringResource(Res.string.speaker_management_no_assignable))
                                TextButton(onClick = onOpenSettings) {
                                    Text(stringResource(Res.string.speaker_management_settings))
                                }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(assignable, key = Speaker::id) { speaker ->
                                SpeakerRow(
                                    speaker = speaker,
                                    summary = summaries[speaker.id],
                                    onClick = {
                                        scope.launch {
                                            val result = speakers.assignOccurrence(sourceOccurrence.id, speaker.id)
                                            if (result.isRight()) {
                                                val previous = sourceOccurrence.toLinkSnapshot()
                                                val expected = SpeakerOccurrenceLink(
                                                    occurrenceId = sourceOccurrence.id,
                                                    speakerId = speaker.id,
                                                    link = SpeakerLink.MANUAL,
                                                    rejectedSpeakerIds = sourceOccurrence.rejectedSpeakerIds
                                                        .filterNot { it == speaker.id },
                                                )
                                                onOccurrenceChanged(expected, previous)
                                                onClose()
                                            } else {
                                                operationFailed = true
                                                retryAssignSpeakerId = speaker.id
                                                if (result.leftOrNull() is SpeakerError.NotFound) {
                                                    message = "This speaker occurrence is no longer available."
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        Button(
                            onClick = {
                                name = ""
                                createVisible = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(Res.string.speaker_management_create_assign))
                        }
                        retryAssignSpeakerId?.let { speakerId ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        speakers.assignOccurrence(sourceOccurrence.id, speakerId).fold(
                                            ifLeft = { operationFailed = true },
                                            ifRight = { onClose() },
                                        )
                                    }
                                },
                            ) { Text(stringResource(Res.string.speaker_management_retry)) }
                        }
                    }
                }

                selectedSpeaker != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedId = null; mergeMode = false }) {
                            Text(stringResource(Res.string.speaker_management_back))
                        }
                        SpeakerColorDot(selectedSpeaker.colorIndex)
                        Text(selectedSpeaker.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    val summary = summaries[selectedSpeaker.id]
                    Text(
                        summary?.let { stringResource(Res.string.speaker_management_linked_count, it.linkedMemoryCount) }
                            ?: stringResource(Res.string.speaker_management_no_memories),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    summary?.lastHeardAtEpochMillis?.let { lastHeard ->
                        Text(
                            stringResource(
                                Res.string.speaker_management_last_heard,
                                formatMemoryTimestamp(lastHeard),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (mergeMode) {
                        Text(stringResource(Res.string.speaker_management_select_target))
                        OutlinedTextField(
                            value = mergeSearch,
                            onValueChange = { mergeSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.speaker_management_search)) },
                            singleLine = true,
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                library.filter {
                                    it.id != selectedSpeaker.id && it.name.contains(mergeSearch, ignoreCase = true)
                                },
                                key = Speaker::id,
                            ) { target ->
                                SpeakerRow(target, summaries[target.id]) {
                                    mergeTarget = target
                                }
                            }
                        }
                        TextButton(onClick = { mergeMode = false }) { Text(stringResource(Res.string.speaker_management_cancel)) }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = { editTarget = selectedSpeaker; name = selectedSpeaker.name },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(Res.string.speaker_management_rename))
                            }
                            OutlinedButton(
                                onClick = { mergeMode = true; mergeSearch = "" },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(Res.string.speaker_management_merge))
                            }
                            OutlinedButton(
                                onClick = { deleteTarget = selectedSpeaker },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(Res.string.speaker_management_delete))
                            }
                        }
                        Text(
                            stringResource(Res.string.speaker_management_recent),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (detailFailed) {
                            ErrorPanel(onRetry = { scope.launch { reload() } })
                        } else if (selectedOccurrences.isEmpty()) {
                            Text(stringResource(Res.string.speaker_management_no_recent))
                        } else {
                            val recent = selectedOccurrences.mapNotNull { occurrence ->
                                memoryList.firstOrNull { it.id == occurrence.memoryId }?.let { it to occurrence }
                            }.sortedByDescending { it.first.createdAtEpochMillis }
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(recent, key = { it.second.id }) { (memory, occurrence) ->
                                    RecentOccurrenceRow(
                                        memory = memory,
                                        occurrence = occurrence,
                                        speakerName = selectedSpeaker.name,
                                        playingTarget = playingTarget,
                                        hasAudio = hasRecordingFile(memory),
                                        onPlaySample = onPlaySample,
                                        onOpen = {
                                            onOpenMemoryAtSpeaker(
                                                SpeakerMemoryNavigation(memory.id, occurrence.ordinal),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                name = ""
                                createVisible = true
                            },
                        ) { Text(stringResource(Res.string.speaker_management_add)) }
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(Res.string.speaker_management_search)) },
                        singleLine = true,
                    )
                    when {
                        loading -> LoadingSpeakerRows(Modifier.weight(1f))
                        loadFailed -> ErrorPanel(onRetry = { scope.launch { reload() } })
                        library.isEmpty() -> EmptyLibrary(onFindMemory = onClose, onOpenSettings = onOpenSettings)
                        else -> {
                            val filtered = library.filter { it.name.contains(search, ignoreCase = true) }
                            if (filtered.isEmpty()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(Res.string.speaker_management_no_results, search))
                                    TextButton(onClick = { search = "" }) {
                                        Text(stringResource(Res.string.speaker_management_clear_search))
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(filtered, key = Speaker::id) { speaker ->
                                        SpeakerRow(speaker, summaries[speaker.id]) {
                                            selectedId = speaker.id
                                            detailFailed = false
                                            scope.launch {
                                                speakers.findOccurrencesForSpeaker(speaker.id).fold(
                                                    ifLeft = { detailFailed = true },
                                                    ifRight = { selectedOccurrences = it },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (createVisible || editTarget != null) {
        val editing = editTarget
        AlertDialog(
            onDismissRequest = {
                createVisible = false
                editTarget = null
                operationFailed = false
            },
            title = { Text(if (editing == null) stringResource(Res.string.speaker_management_add) else stringResource(Res.string.speaker_management_rename)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= MAX_SPEAKER_NAME_LENGTH) name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(Res.string.speaker_management_name)) },
                        supportingText = {
                            Column {
                                Text(stringResource(Res.string.speaker_management_name_helper))
                                Text(
                                    stringResource(
                                        Res.string.speaker_management_name_count,
                                        name.length,
                                        MAX_SPEAKER_NAME_LENGTH,
                                    ),
                                )
                            }
                        },
                        isError = operationFailed,
                        singleLine = true,
                    )
                    if (operationFailed) {
                        Text(
                            message ?: stringResource(Res.string.speaker_management_action_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.trim().isNotEmpty(),
                    onClick = {
                        scope.launch {
                            val result = if (editing == null) speakers.createSpeaker(name) else {
                                speakers.renameSpeaker(editing.id, name).map { editing.copy(name = name.trim()) }
                            }
                            result.fold(
                                ifLeft = { error ->
                                    operationFailed = true
                                    message = error.toUiMessage()
                                },
                                ifRight = { createdOrRenamed ->
                                    operationFailed = false
                                    message = null
                                    createVisible = false
                                    editTarget = null
                                    if (sourceOccurrence != null && editing == null) {
                                        val assignment = speakers.assignOccurrence(sourceOccurrence.id, createdOrRenamed.id)
                                        assignment.fold(
                                            ifLeft = {
                                                retryAssignSpeakerId = createdOrRenamed.id
                                                operationFailed = true
                                                message = "Speaker created, but assignment did not finish. Retry assignment."
                                            },
                                            ifRight = {
                                                onOccurrenceChanged(
                                                    SpeakerOccurrenceLink(
                                                        occurrenceId = sourceOccurrence.id,
                                                        speakerId = createdOrRenamed.id,
                                                        link = SpeakerLink.MANUAL,
                                                        rejectedSpeakerIds = sourceOccurrence.rejectedSpeakerIds
                                                            .filterNot { it == createdOrRenamed.id },
                                                    ),
                                                    sourceOccurrence.toLinkSnapshot(),
                                                )
                                                onClose()
                                            },
                                        )
                                    } else if (editing == null) {
                                        selectedId = createdOrRenamed.id
                                    }
                                },
                            )
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (sourceOccurrence != null && editing == null) {
                                Res.string.speaker_management_create_assign_action
                            } else {
                                Res.string.speaker_management_save
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { createVisible = false; editTarget = null; operationFailed = false }) {
                    Text(stringResource(Res.string.speaker_management_cancel))
                }
            },
        )
    }

    deleteTarget?.let { deleting ->
        val count = summaries[deleting.id]?.linkedMemoryCount ?: 0
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(Res.string.speaker_management_delete_title, deleting.name)) },
            text = { Text(stringResource(Res.string.speaker_management_delete_impact, deleting.name, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            speakers.deleteSpeaker(deleting.id).fold(
                                ifLeft = { operationFailed = true; message = it.toUiMessage() },
                                ifRight = { selectedId = null; deleteTarget = null },
                            )
                        }
                    },
                ) { Text(stringResource(Res.string.speaker_management_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(Res.string.speaker_management_cancel))
                }
            },
        )
    }

    mergeTarget?.let { target ->
        val source = selectedSpeaker
        if (source != null) {
            val count = summaries[source.id]?.linkedMemoryCount ?: 0
            AlertDialog(
                onDismissRequest = { mergeTarget = null },
                title = { Text(stringResource(Res.string.speaker_management_merge_title, source.name, target.name)) },
                text = { Text(stringResource(Res.string.speaker_management_merge_impact, count, target.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                speakers.mergeSpeakers(source.id, target.id).fold(
                                    ifLeft = { operationFailed = true; message = it.toUiMessage() },
                                    ifRight = {
                                        selectedId = target.id
                                        selectedOccurrences = emptyList()
                                        mergeMode = false
                                        mergeTarget = null
                                        scope.launch { reload() }
                                    },
                                )
                            }
                        },
                    ) { Text(stringResource(Res.string.speaker_management_merge_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { mergeTarget = null }) {
                        Text(stringResource(Res.string.speaker_management_cancel))
                    }
                },
            )
        }
    }

    if (rejectChoiceVisible && sourceOccurrence != null) {
        AlertDialog(
            onDismissRequest = { rejectChoiceVisible = false },
            title = { Text(stringResource(Res.string.speaker_management_not_this_person)) },
            text = { Text(stringResource(Res.string.speaker_management_choose_speaker)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            speakers.rejectAutomaticMatch(sourceOccurrence.id).fold(
                                ifLeft = { operationFailed = true; message = it.toUiMessage() },
                                ifRight = {
                                    onOccurrenceChanged(
                                        SpeakerOccurrenceLink(
                                            occurrenceId = sourceOccurrence.id,
                                            speakerId = null,
                                            link = SpeakerLink.NONE,
                                            rejectedSpeakerIds = (sourceOccurrence.rejectedSpeakerIds +
                                                requireNotNull(sourceOccurrence.speakerId)).distinct(),
                                        ),
                                        sourceOccurrence.toLinkSnapshot(),
                                    )
                                    onClose()
                                },
                            )
                        }
                        rejectChoiceVisible = false
                    },
                ) { Text(stringResource(Res.string.speaker_management_leave_unknown)) }
            },
            dismissButton = {
                TextButton(onClick = { rejectChoiceVisible = false }) {
                    Text(stringResource(Res.string.speaker_management_choose_another))
                }
            },
        )
    }

    if (operationFailed && createVisible.not()) {
        TextButton(onClick = { operationFailed = false; message = null }) {
            Text(message ?: stringResource(Res.string.speaker_management_action_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SpeakerRow(
    speaker: Speaker,
    summary: SpeakerSummary?,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeakerColorDot(speaker.colorIndex)
            Column(modifier = Modifier.weight(1f)) {
                Text(speaker.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val count = summary?.linkedMemoryCount ?: 0
                Text(
                    if (count == 0) stringResource(Res.string.speaker_management_no_memories)
                    else stringResource(Res.string.speaker_management_linked_count, count),
                    style = MaterialTheme.typography.bodySmall,
                )
                summary?.lastHeardAtEpochMillis?.let {
                    Text(
                        stringResource(
                            Res.string.speaker_management_last_heard,
                            formatMemoryTimestamp(it),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SpeakerColorDot(colorIndex: Int) {
    Box(
        modifier = Modifier
            .background(Color(speakerPalette[colorIndex].toULong()), CircleShape)
            .padding(12.dp)
            .semantics { contentDescription = "Speaker color" },
    )
}

@Composable
private fun RecentOccurrenceRow(
    memory: Memory,
    occurrence: SpeakerOccurrence,
    speakerName: String,
    playingTarget: PlaybackTarget?,
    hasAudio: Boolean,
    onPlaySample: (PlaybackTarget) -> Unit,
    onOpen: () -> Unit,
) {
    val target = sampleTarget(occurrence)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(memory.title.ifBlank { stringResource(Res.string.speaker_management_untitled) }, fontWeight = FontWeight.SemiBold)
            Text(
                formatMemoryTimestamp(memory.createdAtEpochMillis),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Speaker ${occurrence.ordinal} · $speakerName", style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    if (occurrence.link == SpeakerLink.AUTO) {
                        Res.string.speaker_management_matched
                    } else {
                        Res.string.speaker_management_assigned
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasAudio) {
                    TextButton(onClick = { onPlaySample(target) }) {
                        Text(
                            stringResource(
                                if (playingTarget == target) Res.string.speaker_management_stop_sample
                                else Res.string.speaker_management_play_sample,
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = "${if (playingTarget == target) "Stop" else "Play"} sample for $speakerName in ${memory.title}"
                            },
                        )
                    }
                } else {
                    Text(stringResource(Res.string.speaker_management_audio_unavailable))
                }
                TextButton(onClick = onOpen) {
                    Text(stringResource(Res.string.speaker_management_open_memory))
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onFindMemory: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(Res.string.speaker_management_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(Res.string.speaker_management_empty_body))
        Button(onClick = onFindMemory) { Text(stringResource(Res.string.speaker_management_find_memory)) }
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(Res.string.speaker_management_settings))
        }
    }
}

@Composable
private fun ErrorPanel(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.speaker_management_load_failed), color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text(stringResource(Res.string.speaker_management_retry)) }
    }
}

@Composable
private fun LoadingSpeakerRows(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(3) {
            Card(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
    }
}

private fun sampleTarget(occurrence: SpeakerOccurrence) = PlaybackTarget(
    memoryId = occurrence.memoryId,
    content = PlaybackContent.SPEAKER_SAMPLE,
    sample = PlaybackSample(occurrence.sampleStartMs, occurrence.sampleEndMs),
)

private fun SpeakerOccurrence.toLinkSnapshot() = SpeakerOccurrenceLink(
    occurrenceId = id,
    speakerId = speakerId,
    link = link,
    rejectedSpeakerIds = rejectedSpeakerIds,
)

private fun SpeakerError.toUiMessage(): String = when (this) {
    is SpeakerError.NameTaken -> "A speaker named “$name” already exists."
    is SpeakerError.InvalidInput -> reason
    is SpeakerError.NotFound -> "This speaker occurrence is no longer available."
    else -> "Couldn’t update speaker. Try again."
}
