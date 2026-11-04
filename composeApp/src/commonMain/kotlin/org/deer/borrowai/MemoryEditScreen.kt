package org.deer.borrowai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import arrow.core.Either
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_edit
import borrowai.composeapp.generated.resources.memory_edit_cancel
import borrowai.composeapp.generated.resources.memory_edit_counter
import borrowai.composeapp.generated.resources.memory_edit_discard
import borrowai.composeapp.generated.resources.memory_edit_discard_title
import borrowai.composeapp.generated.resources.memory_edit_failed
import borrowai.composeapp.generated.resources.memory_edit_keep_editing
import borrowai.composeapp.generated.resources.memory_edit_retry
import borrowai.composeapp.generated.resources.memory_edit_save
import borrowai.composeapp.generated.resources.memory_edit_summary_changed
import borrowai.composeapp.generated.resources.memory_edit_summary_helper
import borrowai.composeapp.generated.resources.memory_edit_summary_label
import borrowai.composeapp.generated.resources.memory_edit_summary_too_long
import borrowai.composeapp.generated.resources.memory_edit_title_label
import borrowai.composeapp.generated.resources.memory_edit_title_too_long
import org.deer.borrowai.data.domain.MEMORY_SUMMARY_MAX_LENGTH
import org.deer.borrowai.data.domain.MEMORY_TITLE_MAX_LENGTH
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryEdit
import org.deer.borrowai.data.domain.currentEdit
import org.deer.borrowai.data.service.MemoryError
import org.jetbrains.compose.resources.stringResource

/** Counter appears from this many title characters, so the limit is not on screen all the time (AC-2.1). */
private const val TITLE_COUNTER_THRESHOLD = 60

/**
 * Full-screen editor for a memory's title and summary. The transcript and categories are not editable here yet, so
 * [MemoryEdit] carries their current values unchanged. [onSave] reports the outcome through its callback.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MemoryEditScreen(
    memory: Memory,
    onSave: (String?, String?, String, (Either<MemoryError, Unit>) -> Unit) -> Unit,
    onClose: () -> Unit,
    onDeletedElsewhere: () -> Unit,
) {
    var title by rememberSaveable(memory.id) { mutableStateOf(memory.title) }
    var summary by rememberSaveable(memory.id) { mutableStateOf(memory.summary) }
    var titleEdited by rememberSaveable(memory.id) { mutableStateOf(false) }
    var summaryEdited by rememberSaveable(memory.id) { mutableStateOf(false) }
    // The summary the editor opened with. A different live summary means a new one was written during the edit.
    val summaryAtEntry by rememberSaveable(memory.id) { mutableStateOf(memory.summary) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val keepEditingFocus = remember { FocusRequester() }

    val live = memory.currentEdit()
    // Only the fields the user typed in are sent. Any other field keeps its live value, so a background summary is not
    // replaced by text from when the editor opened.
    val draft = live.copy(
        title = if (titleEdited) title else live.title,
        summary = if (summaryEdited) summary else live.summary,
    )
    val changed = draft != live
    val summaryConflict = summaryEdited && memory.summary != summaryAtEntry
    val titleTooLong = title.length > MEMORY_TITLE_MAX_LENGTH
    val summaryTooLong = summary.length > MEMORY_SUMMARY_MAX_LENGTH
    // The service still checks the transcript and categories and reports a refusal, so a failed transcript that is
    // blank does not keep the title from saving here.
    val valid = !titleTooLong && !summaryTooLong

    fun save() {
        if (saving || !valid || !changed || summaryConflict) return
        saving = true
        failed = false
        rejected = null
        onSave(
            if (titleEdited) title else null,
            if (summaryEdited) summary else null,
            summaryAtEntry,
        ) { result ->
            saving = false
            result.fold(
                ifLeft = { error ->
                    when (error) {
                        is MemoryError.NotFound -> onDeletedElsewhere()
                        is MemoryError.InvalidInput -> rejected = error.reason
                        else -> failed = true
                    }
                },
                ifRight = { onClose() },
            )
        }
    }

    fun cancel() {
        if (changed) confirmDiscard = true else onClose()
    }

    // System back is the same as Cancel (AC-7.2).
    BackHandler { cancel() }

    LaunchedEffect(confirmDiscard) {
        if (confirmDiscard) runCatching { keepEditingFocus.requestFocus() }
    }

    val cancelLabel = stringResource(Res.string.memory_edit_cancel)
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = ::cancel,
                modifier = Modifier.size(48.dp).semantics { contentDescription = cancelLabel },
            ) {
                Text("\u00D7", modifier = Modifier.clearAndSetSemantics { })
            }
            Text(
                stringResource(Res.string.memory_edit),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = ::save,
                enabled = changed && valid && !saving && !summaryConflict,
                modifier = Modifier.size(width = 96.dp, height = 48.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Res.string.memory_edit_save))
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    titleEdited = true
                    rejected = null
                    failed = false
                },
                label = { Text(stringResource(Res.string.memory_edit_title_label)) },
                singleLine = true,
                readOnly = saving,
                isError = titleTooLong,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                supportingText = {
                    Text(
                        when {
                            titleTooLong -> stringResource(Res.string.memory_edit_title_too_long, MEMORY_TITLE_MAX_LENGTH)
                            title.length >= TITLE_COUNTER_THRESHOLD ->
                                stringResource(Res.string.memory_edit_counter, title.length, MEMORY_TITLE_MAX_LENGTH)
                            else -> ""
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = summary,
                onValueChange = {
                    summary = it
                    summaryEdited = true
                    rejected = null
                    failed = false
                },
                label = { Text(stringResource(Res.string.memory_edit_summary_label)) },
                readOnly = saving,
                isError = summaryTooLong,
                minLines = 4,
                supportingText = {
                    Text(
                        if (summaryTooLong) {
                            stringResource(Res.string.memory_edit_summary_too_long, MEMORY_SUMMARY_MAX_LENGTH)
                        } else {
                            stringResource(Res.string.memory_edit_summary_helper)
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            rejected?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (summaryConflict) {
                Text(
                    stringResource(Res.string.memory_edit_summary_changed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.memory_edit_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = ::save, modifier = Modifier.size(height = 48.dp, width = 96.dp)) {
                        Text(stringResource(Res.string.memory_edit_retry))
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(Res.string.memory_edit_discard_title)) },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(Res.string.memory_edit_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDiscard = false },
                    modifier = Modifier.focusRequester(keepEditingFocus),
                ) {
                    Text(stringResource(Res.string.memory_edit_keep_editing))
                }
            },
        )
    }
}
